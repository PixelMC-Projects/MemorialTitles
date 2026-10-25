package cn.pixelmc.memorialtitles;

import static cn.pixelmc.memorialtitles.domain.Model.*;
import cn.pixelmc.memorialtitles.persistence.Database;
import cn.pixelmc.memorialtitles.service.*;
import cn.pixelmc.memorialtitles.web.*;
import java.nio.file.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;

/** Keeps the same production web/service code available for local acceptance against an isolated DB. */
public final class WebAcceptanceHarness {
  public static void main(String[] args)throws Exception{
    String password=System.getenv("MT_TEST_PASSWORD");if(password==null)throw new IllegalStateException("Set MT_TEST_PASSWORD");
    var root=new Database(new Database.Config("jdbc:mariadb://127.0.0.1:43307/","root",password));
    try(Connection c=root.open()){Database.execute(c,"CREATE DATABASE IF NOT EXISTS mt_web_acceptance CHARACTER SET utf8mb4");}
    Path folder=Path.of("run-web-acceptance").toAbsolutePath(), backups=folder.resolve("backups");Files.createDirectories(folder);
    var s=new TitleService(new Database(new Database.Config("jdbc:mariadb://127.0.0.1:43307/mt_web_acceptance","root",password)));
    s.reconnect().get(20,TimeUnit.SECONDS);UUID admin=UUID.fromString("7520aa16-9d82-324e-acf4-7ecbf7aa436d"), alex=UUID.fromString("f0063548-23c3-41f9-8e39-42801f495193");var console=TitleService.Actor.consoleActor();
    if(s.snapshot().players().isEmpty()){
      s.login(admin,"MTTester").get();s.login(alex,"Alex").get();s.authorize(console,admin,true).get();
      s.saveTitle(console,new Title("season_one",new RichText(List.of(new Segment("山海同行者·第一季",0xffffff,true,false,false,false)),List.of(new Gradient(List.of(0,1,2,3,4),0x67e8f9,0xc084fc))),"第一季共同走过的旅程\n纪念我们的相遇。","香草纪元 2.7.1","管理员纪念发放","赛季纪念",1,7,false,true,0),0).get();
      s.saveTitle(console,new Title("first_journey",new RichText(List.of(new Segment("启程之约",0xdfb778,false,false,false,false)),List.of()),"从这里启程，和熟悉的人一起走向新的世界。","香草纪元 2.7.1","活动纪念","活动纪念",2,0,true,true,0),0).get();
      s.grant(console,List.of(admin,alex),"season_one","本地验收初始资料").get();s.grant(console,List.of(alex),"first_journey","本地验收初始资料").get();s.privacy(alex,"season_one",true,true).get();s.equip(alex,"season_one").get();
    }
    AdminWebServer.Host host=new AdminWebServer.Host(){
      public Set<UUID> online(){return Set.of();}
      public void mirror(){try{Files.writeString(folder.resolve("definitions.json"),JSON.toJson(AdminWebServer.definitions(s.snapshot())));}catch(Exception e){throw new RuntimeException(e);}}
      public CompletableFuture<Path> backup(TitleService.Actor actor){return s.export(actor).thenCompose(a->s.background(()->{Path p=Backup.write(backups,a);Backup.retain(backups,a.settings().retentionDays());return p;}));}
      public CompletableFuture<Void> reload(TitleService.Actor actor){return s.background(()->JSON.fromJson(Files.readString(folder.resolve("definitions.json")),AdminWebServer.Definitions.class)).thenCompose(d->s.reload(actor,d.titles(),d.settings()));}
    };
    int port=Integer.parseInt(System.getenv().getOrDefault("MT_WEB_PREVIEW_PORT","18765"));
    String previewUrl="http://127.0.0.1:"+port;
    var web=new AdminWebServer(s,host,new WebConfig(true,"127.0.0.1",port,previewUrl,480,List.of("http://127.0.0.1:4173")),folder,backups);host.mirror();
    Path credentials=Path.of("tools/web-acceptance.local.json");
    if(web.accounts.get("admin")==null){String initial=AccountStore.randomPassword();web.accounts.create("admin",admin,initial);Files.writeString(credentials,JSON.toJson(Map.of("username","admin","password",initial,"url","http://127.0.0.1:18765","environment","local acceptance only")));}
    Runtime.getRuntime().addShutdownHook(new Thread(()->{web.close();s.close();}));
    System.out.println("MEMORIALTITLES_WEB_ACCEPTANCE_READY "+previewUrl+" (local test accounts only)");
    new CountDownLatch(1).await();
  }
}
