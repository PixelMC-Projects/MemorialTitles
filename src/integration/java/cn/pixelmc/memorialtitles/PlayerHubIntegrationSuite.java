package cn.pixelmc.memorialtitles;

import static cn.pixelmc.memorialtitles.domain.Model.*;

import cn.pixelmc.memorialtitles.api.PlayerHubApi;
import cn.pixelmc.memorialtitles.persistence.*;
import cn.pixelmc.memorialtitles.service.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Disposable local MariaDB only. The launcher owns the isolated process at 127.0.0.1:43319. */
public final class PlayerHubIntegrationSuite {
  private static final List<String> checks=new ArrayList<>();
  private static final TitleService.Actor ADMIN=TitleService.Actor.consoleActor();
  private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks.add(label);System.out.println("PASS "+label);}
  private static <T>T await(CompletableFuture<T> value)throws Exception{return value.get(25,TimeUnit.SECONDS);}
  private static void deny(CompletableFuture<?> future,String label)throws Exception{try{await(future);throw new AssertionError(label);}catch(ExecutionException expected){check(true,label);}}
  private static JsonObject body(Object...pairs){JsonObject out=new JsonObject();for(int i=0;i<pairs.length;i+=2)out.add((String)pairs[i],JSON.toJsonTree(pairs[i+1]));return out;}
  private static Title title(String id,Presentation presentation){return new Title(id,RichText.plain(id),"description","pack","informational text","纪念",0,777,false,true,0,0,presentation);}
  private static JsonObject hub(TitleService service,UUID player,String action,JsonObject payload)throws Exception{return await(service.playerHub(player,action,payload));}
  public static void main(String[]args)throws Exception{
    if(!"isolated".equals(System.getenv("MT_HUB_TEST_SCOPE")))throw new IllegalStateException("Requires isolated test launcher");
    String base="jdbc:mariadb://127.0.0.1:43319/", name="mt_hub_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
    Database root=new Database(new Database.Config(base,"root",""));
    try(var c=root.open()){Database.execute(c,"CREATE DATABASE "+name+" CHARACTER SET utf8mb4");}
    Database db=new Database(new Database.Config(base+name,"root",""));
    try(TitleService service=new TitleService(db)){
      service.attachHubAdmission(id -> true); // Only this isolated fixture; runtime defaults deny.
      await(service.reconnect()); UUID player=UUID.randomUUID(),other=UUID.randomUUID();
      await(service.login(player,"Player"));await(service.login(other,"Other"));
      Presentation original=new Presentation("personal","rare","sage");
      await(service.saveTitle(ADMIN,title("owned",original),0));await(service.saveTitle(ADMIN,title("secret",Presentation.defaults()),0));
      await(service.grant(ADMIN,List.of(player),"owned","fixture"));await(service.grant(ADMIN,List.of(other),"secret","fixture"));
      JsonObject first=hub(service,player,"snapshot",new JsonObject());
      check(first.getAsJsonArray("titles").size()==1,"real SQL response excludes another player's hidden title");
      await(service.setUnequippedReminder(player,false));
      JsonObject equipped=hub(service,player,"equip",body("title","owned","expectedRevision",first.get("revision").getAsString()));
      check(equipped.get("equippedTitle").getAsString().equals("owned")&&service.snapshot().hasEquippedTitle(player),"equip acknowledges committed owned title");
      deny(service.playerHub(player,"visibility",body("visible",false,"expectedRevision",first.get("revision").getAsString())),"stale mutation revision rejects before writing");
      JsonObject hidden=hub(service,player,"visibility",body("visible",false,"expectedRevision",equipped.get("revision").getAsString()));
      Owned ownership=service.snapshot().ownership(player,"owned");
      check(!ownership.publicDisplay()&&!ownership.publicCollection()&&service.snapshot().hasEquippedTitle(player),"visibility changes display only while preserving collection privacy and equip");
      JsonObject repeat=hub(service,player,"visibility",body("visible",false,"expectedRevision",hidden.get("revision").getAsString()));
      check(repeat.get("revision").equals(hidden.get("revision")),"repeated explicit visibility is idempotent");
      Settings settings=service.snapshot().settings();await(service.settings(ADMIN,new Settings(settings.reward(),false,true,false,settings.headScale(),settings.headOffset(),settings.chatPrefix(),settings.retentionDays(),settings.packName(),settings.packStartedOn())));
      JsonObject current=hub(service,player,"snapshot",new JsonObject());
      JsonObject preferences=hub(service,player,"displayPreferences",body("head",true,"chat",false,"tab",true,"expectedRevision",current.get("revision").getAsString()));
      check(!service.snapshot().displayPreferences(player).reminderEnabled()&&!preferences.getAsJsonObject("globalDisplay").get("head").getAsBoolean()&&!preferences.getAsJsonObject("globalDisplay").get("tab").getAsBoolean()&&!service.snapshot().displayPreferences(player).chat(),"display prefs retain reminder and global administrator restrictions");
      deny(service.playerHub(player,"equip",body("title","secret","expectedRevision",preferences.get("revision").getAsString())),"cannot equip another player's unowned hidden title");
      Title before=service.snapshot().titles().get("owned");
      Title retired=new Title(before.id(),before.name(),before.description(),before.source(),before.acquisition(),before.category(),before.order(),before.icon(),false,false,before.revision(),before.createdAt(),before.presentation());
      await(service.saveTitle(ADMIN,retired,before.revision()));
      current=hub(service,player,"snapshot",new JsonObject());hub(service,player,"equip",body("title","","expectedRevision",current.get("revision").getAsString()));
      current=hub(service,player,"snapshot",new JsonObject());hub(service,player,"equip",body("title","owned","expectedRevision",current.get("revision").getAsString()));
      check(service.snapshot().hasEquippedTitle(player),"retired owned title can be unequipped and reequipped");
      before=service.snapshot().titles().get("owned");Title edited=new Title(before.id(),before.name(),before.description(),before.source(),before.acquisition(),before.category(),before.order(),before.icon(),false,false,before.revision(),before.createdAt(),new Presentation("star","treasured","blue"));await(service.saveTitle(ADMIN,edited,before.revision()));
      long audit=await(service.history(ADMIN,100)).stream().filter(a->a.action().equals("TITLE_SAVE")).max(Comparator.comparingLong(Audit::id)).orElseThrow().id();
      List<ChangeJournal.View> changes=await(service.changes(ADMIN,audit));
      long badge=changes.stream().filter(v->v.label().equals("卡片徽章")).findFirst().orElseThrow().id();
      before=service.snapshot().titles().get("owned");edited=new Title(before.id(),before.name(),"later unrelated description",before.source(),before.acquisition(),before.category(),before.order(),before.icon(),false,false,before.revision(),before.createdAt(),new Presentation("star","treasured","rose"));await(service.saveTitle(ADMIN,edited,before.revision()));
      await(service.undo(ADMIN,badge));Title undone=service.snapshot().titles().get("owned");
      check(undone.presentation().badge().equals("personal")&&undone.presentation().theme().equals("rose")&&undone.presentation().rarity().equals("treasured")&&undone.description().equals("later unrelated description")&&undone.icon()==777,"SQL selective undo changes badge only and retains later theme description rarity and CustomModelData");
      Backup.Archive archive=await(service.export(ADMIN));Backup.Archive roundtrip=JSON.fromJson(JSON.toJson(archive),Backup.Archive.class);Backup.validate(roundtrip);
      check(roundtrip.titles().stream().filter(t->t.id().equals("owned")).findFirst().orElseThrow().equals(undone),"backup capture validation and JSON import preserve all presentation metadata");
      await(service.reconnect());check(service.snapshot().titles().get("owned").equals(undone),"database reload preserves card metadata");
      AtomicBoolean admitted=new AtomicBoolean(true);PlayerHubApi.attach(service,id->id.equals(player)&&admitted.get());
      deny(PlayerHubApi.request(other,"snapshot","{}"),"runtime boundary rejects offline or unauthenticated UUID");
      deny(PlayerHubApi.request(player,"snapshot","{\"player\":\"other\"}"),"runtime boundary rejects forged target payload");
      JsonObject boundary=JsonParser.parseString(await(PlayerHubApi.request(player,"snapshot","{}"))).getAsJsonObject();
      check(boundary.getAsJsonArray("titles").size()==1&&!boundary.has("players")&&!boundary.has("admins")&&boundary.has("nextCursor")&&boundary.get("nextCursor").isJsonNull(),"public static boundary emits safe own-player envelope and preserves terminal null cursor");
      admitted.set(false);deny(PlayerHubApi.request(player,"equip",JSON.toJson(body("title","","expectedRevision",boundary.get("revision").getAsString()))),"logged-out session cannot mutate queued player state");
      PlayerHubApi.attach(null,null);
    }finally{PlayerHubApi.attach(null,null);try(var c=root.open()){Database.execute(c,"DROP DATABASE "+name);}}
    Path report=Path.of("build/reports/player-hub-integration.json");Files.createDirectories(report.getParent());Files.writeString(report,JSON.toJson(Map.of("passed",true,"checks",checks)));
  }
}
