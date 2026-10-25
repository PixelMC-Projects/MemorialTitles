package cn.pixelmc.memorialtitles;

import static cn.pixelmc.memorialtitles.domain.Model.*;
import cn.pixelmc.memorialtitles.persistence.*;
import cn.pixelmc.memorialtitles.service.*;
import cn.pixelmc.memorialtitles.web.AvatarStore;
import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;

/** Real MariaDB tests for selective inverse patches, conflict refusal and recoverable erasure. */
public final class UndoIntegrationSuite {
  private static final List<String> checks=new ArrayList<>();
  private static final TitleService.Actor ADMIN=TitleService.Actor.consoleActor();
  private static <T>T waitFor(CompletableFuture<T> future)throws Exception{return future.get(25,TimeUnit.SECONDS);}
  private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks.add(label);System.out.println("PASS "+label);}
  private static void deny(CompletableFuture<?> future,String label)throws Exception{boolean failed=false;try{waitFor(future);}catch(ExecutionException ex){failed=true;}check(failed,label);}
  private static Title title(String id){return new Title(id,RichText.plain(id),"原说明","原来源","","纪念",0,0,true,true,0);}
  private static void edit(TitleService s,String id,String field,String value)throws Exception{Title old=s.snapshot().titles().get(id);JsonObject j=JSON.toJsonTree(old).getAsJsonObject();if(field.equals("name"))j.add(field,JSON.toJsonTree(RichText.plain(value)));else j.addProperty(field,value);waitFor(s.saveTitle(ADMIN,JSON.fromJson(j,Title.class),old.revision()));}
  private static long lastAudit(TitleService s,String action)throws Exception{return waitFor(s.history(ADMIN,1000)).stream().filter(a->a.action().equals(action)).findFirst().orElseThrow().id();}
  private static List<ChangeJournal.View> changes(TitleService s,String action)throws Exception{return waitFor(s.changes(ADMIN,lastAudit(s,action)));}
  private static long change(TitleService s,String action)throws Exception{return changes(s,action).get(0).id();}

  public static void main(String[] args)throws Exception{
    String password=Objects.requireNonNull(System.getenv("MT_TEST_PASSWORD"));String base="jdbc:mariadb://127.0.0.1:43307/";
    String name="mt_undo_"+UUID.randomUUID().toString().replace("-","").substring(0,10),restoredName=name+"_restore";
    Database root=new Database(new Database.Config(base,"root",password));
    try(var c=root.open()){Database.execute(c,"CREATE DATABASE "+name+" CHARACTER SET utf8mb4");Database.execute(c,"CREATE DATABASE "+restoredName+" CHARACTER SET utf8mb4");}
    Database db=new Database(new Database.Config(base+name,"root",password));
    Set<UUID> online=ConcurrentHashMap.newKeySet();UUID admin=UUID.randomUUID(),bob=UUID.randomUUID(),carl=UUID.randomUUID(),dave=UUID.randomUUID();
    Path assetsPath=Path.of("build/undo-assets/"+name);
    try(var avatars=new AvatarStore(assetsPath);var s=new TitleService(db);var second=new TitleService(db);var restoredAssets=new AvatarStore(assetsPath.resolve("restored"));var restored=new TitleService(new Database(new Database.Config(base+restoredName,"root",password)))){
      waitFor(s.reconnect());waitFor(second.reconnect());s.attachPlayerAssets(avatars,online::contains);second.attachPlayerAssets(avatars,online::contains);restored.attachPlayerAssets(restoredAssets,id->false);
      waitFor(s.login(admin,"Owner"));waitFor(s.login(bob,"Bob"));waitFor(s.login(carl,"Carl"));waitFor(s.login(dave,"Dave"));waitFor(s.authorize(ADMIN,admin,true));
      waitFor(s.saveTitle(ADMIN,title("alpha"),0));waitFor(s.saveTitle(ADMIN,title("beta"),0));
      Title old=s.snapshot().titles().get("alpha");JsonObject edited=JSON.toJsonTree(old).getAsJsonObject();edited.addProperty("description","新说明");edited.add("name",JSON.toJsonTree(RichText.plain("新名字")));
      waitFor(s.saveTitle(ADMIN,JSON.fromJson(edited,Title.class),old.revision()));var fields=changes(s,"TITLE_SAVE");
      check(fields.size()==2,"one title edit records separate changed fields");
      long description=fields.stream().filter(v->v.label().equals("称号说明")).findFirst().orElseThrow().id();long displayName=fields.stream().filter(v->v.label().equals("称号文字与样式")).findFirst().orElseThrow().id();
      edit(s,"alpha","source","后续来源");waitFor(s.undo(ADMIN,description));
      check(s.snapshot().titles().get("alpha").description().equals("原说明")&&s.snapshot().titles().get("alpha").source().equals("后续来源")&&s.snapshot().titles().get("alpha").name().plain().equals("新名字"),"undo one field preserves other fields and later unrelated edit");
      edit(s,"alpha","name","更后面的名字");Snapshot before=s.snapshot();awaitDeniedUnchanged(s,displayName,before,"same-field conflict refuses all writes");
      edit(s,"alpha","name","新名字");awaitDeniedUnchanged(s,displayName,s.snapshot(),"change-away-and-back still detected by cell version");
      Settings original=s.snapshot().settings();JsonObject settings=JSON.toJsonTree(original).getAsJsonObject();settings.addProperty("head",false);settings.addProperty("packName","本期整合包");waitFor(s.settings(ADMIN,JSON.fromJson(settings,Settings.class)));
      long head=changes(s,"SETTINGS").stream().filter(v->v.label().equals("头顶展示")).findFirst().orElseThrow().id();settings=JSON.toJsonTree(s.snapshot().settings()).getAsJsonObject();settings.addProperty("packStartedOn","2026-10-23");waitFor(s.settings(ADMIN,JSON.fromJson(settings,Settings.class)));waitFor(s.undo(ADMIN,head));
      check(s.snapshot().settings().head()&&s.snapshot().settings().packName().equals("本期整合包")&&s.snapshot().settings().packStartedOn().equals("2026-10-23"),"undo setting keeps later independent settings");
      waitFor(s.grant(ADMIN,List.of(bob),"alpha","test"));long grant=change(s,"GRANT");waitFor(s.grant(ADMIN,List.of(carl),"alpha","test"));waitFor(s.undo(ADMIN,grant));
      check(s.snapshot().ownership(bob,"alpha")==null&&s.snapshot().ownership(carl,"alpha")!=null,"undo grant affects only that player's ownership");
      deny(s.undo(ADMIN,grant),"same change cannot be undone twice");
      waitFor(s.grant(ADMIN,List.of(bob),"alpha","test"));long changedGrant=change(s,"GRANT");waitFor(s.privacy(bob,"alpha",true,true));long collection=change(s,"PRIVACY");awaitDeniedUnchanged(s,changedGrant,s.snapshot(),"later privacy prevents removing changed grant");waitFor(s.privacy(bob,"alpha",true,false));waitFor(s.undo(ADMIN,collection));
      check(!s.snapshot().ownership(bob,"alpha").publicCollection()&&!s.snapshot().ownership(bob,"alpha").publicDisplay(),"undo one privacy field preserves the other privacy change");
      waitFor(s.equip(carl,"alpha"));Owned prior=s.snapshot().ownership(carl,"alpha");waitFor(s.revoke(ADMIN,List.of(carl),"alpha","test"));long revoke=change(s,"REVOKE");waitFor(s.undo(ADMIN,revoke));
      check(s.snapshot().ownership(carl,"alpha").equals(prior)&&s.snapshot().equipped().get(carl).equals("alpha"),"revoke reversal restores original acquisition and automatic unequip together");
      long undoRevoke=change(s,"UNDO");waitFor(s.undo(ADMIN,undoRevoke));check(s.snapshot().ownership(carl,"alpha")==null&&!s.snapshot().equipped().containsKey(carl),"undo operation itself can be reversed without snapshot restore");
      deny(s.undo(TitleService.Actor.player(bob),undoRevoke),"unauthorized player cannot undo");
      waitFor(s.grant(ADMIN,List.of(bob),"beta","test"));long betaGrant=change(s,"GRANT");edit(s,"beta","description","称号后续修订");awaitDeniedUnchanged(s,betaGrant,s.snapshot(),"referenced title changes stop ownership reversal");
      waitFor(s.grant(ADMIN,List.of(dave),"alpha","concurrency"));long concurrent=change(s,"GRANT");var f1=s.undo(ADMIN,concurrent);var f2=second.undo(ADMIN,concurrent);int successes=0;for(var f:List.of(f1,f2))try{waitFor(f);successes++;}catch(ExecutionException ignored){}
      check(successes==1,"two instances cannot undo the same change twice");waitFor(s.reconnect());
      waitFor(s.equip(bob,"alpha"));settings=JSON.toJsonTree(s.snapshot().settings()).getAsJsonObject();settings.addProperty("reward","alpha");waitFor(s.settings(ADMIN,JSON.fromJson(settings,Settings.class)));waitFor(s.login(bob,"Bob"));
      var image=new BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,0xffffaa00);var output=new ByteArrayOutputStream();ImageIO.write(image,"png",output);JsonObject avatar=new JsonObject();avatar.addProperty("mojang",Base64.getEncoder().encodeToString(output.toByteArray()));avatars.swap(bob,JsonNull.INSTANCE,avatar);
      online.add(bob);deny(s.deletePreview(ADMIN,bob),"online player deletion is refused");online.remove(bob);deny(s.deletePreview(ADMIN,admin),"administrator identity cannot be erased from player UI");
      var preview=waitFor(s.deletePreview(ADMIN,bob));check(preview.owned()==2&&preview.receipts()==1&&preview.history()>0&&preview.avatar(),"deletion preview counts exact affected records");
      deny(s.deletePlayer(ADMIN,bob,preview.stamp(),"not Bob"),"deletion requires exact player-name confirmation");
      waitFor(s.privacy(bob,"alpha",true,false));deny(s.deletePlayer(ADMIN,bob,preview.stamp(),"Bob"),"preview becomes invalid after player data changes");
      preview=waitFor(s.deletePreview(ADMIN,bob));Snapshot beforeFailedDelete=s.snapshot();
      try(var connection=db.open()){Database.execute(connection,"CREATE TRIGGER reject_change BEFORE INSERT ON mt_changes FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='integration failure'");}
      deny(s.deletePlayer(ADMIN,bob,preview.stamp(),"Bob"),"late SQL failure rolls back deletion");
      try(var connection=db.open()){Database.execute(connection,"DROP TRIGGER reject_change");}waitFor(s.reconnect());
      check(s.snapshot().equals(beforeFailedDelete)&&avatars.read(bob).equals(avatar),"late SQL failure restores business data and avatar files together");
      preview=waitFor(s.deletePreview(ADMIN,bob));Owned oldAlpha=s.snapshot().ownership(bob,"alpha"),oldBeta=s.snapshot().ownership(bob,"beta");waitFor(s.deletePlayer(ADMIN,bob,preview.stamp(),"Bob"));long deletion=change(s,"PLAYER_DELETE");
      check(!s.snapshot().players().containsKey(bob)&&s.snapshot().owned().keySet().stream().noneMatch(k->k.player().equals(bob))&&!s.snapshot().equipped().containsKey(bob)&&s.snapshot().receipts().keySet().stream().noneMatch(k->k.player().equals(bob))&&avatars.read(bob).isJsonNull(),"deletion clears profile ownership equip receipts and active avatars");
      check(waitFor(s.searchHistory(ADMIN,"","",bob.toString(),"",0,100)).rows().size()==1,"related old history is removed from normal history while recovery action stays");
      waitFor(s.grant(ADMIN,List.of(carl),"alpha","unrelated after deletion"));waitFor(s.undo(ADMIN,deletion));
      check(s.snapshot().ownership(bob,"alpha").equals(oldAlpha)&&s.snapshot().ownership(bob,"beta").equals(oldBeta)&&s.snapshot().equipped().get(bob).equals("alpha")&&s.snapshot().receipts().containsKey(new Key(bob,"alpha"))&&s.snapshot().ownership(carl,"alpha")!=null&&avatars.read(bob).equals(avatar),"delete reversal restores only affected player and avatar, preserving others");
      check(waitFor(s.searchHistory(ADMIN,"","",bob.toString(),"",0,100)).rows().size()>2,"delete reversal makes related original history visible again");
      preview=waitFor(s.deletePreview(ADMIN,bob));waitFor(s.deletePlayer(ADMIN,bob,preview.stamp(),"Bob"));long afterRelogin=change(s,"PLAYER_DELETE");waitFor(s.login(bob,"Bob"));awaitDeniedUnchanged(s,afterRelogin,s.snapshot(),"new authenticated login after deletion blocks restoration over new profile");
      preview=waitFor(s.deletePreview(ADMIN,bob));waitFor(s.deletePlayer(ADMIN,bob,preview.stamp(),"Bob"));long backupDelete=change(s,"PLAYER_DELETE");
      Backup.Archive archive=waitFor(s.export(ADMIN));check(archive.format()==3&&!archive.journal().entries().isEmpty()&&!archive.journal().hidden().isEmpty(),"backup contains inverse patches versions and protected hidden history");
      waitFor(restored.reconnect());waitFor(restored.restoreEmpty(ADMIN,archive));check(restored.snapshot().equals(s.snapshot()),"backup restores business state without changing undo preconditions");waitFor(restored.undo(ADMIN,backupDelete));check(restored.snapshot().players().containsKey(bob),"undo remains available after database backup and restore");
      // The original deletion still refuses a changed referenced definition rather than forcing it.
      waitFor(s.login(bob,"Bob"));waitFor(s.grant(ADMIN,List.of(bob),"beta","test"));preview=waitFor(s.deletePreview(ADMIN,bob));waitFor(s.deletePlayer(ADMIN,bob,preview.stamp(),"Bob"));long changedTitleDeletion=change(s,"PLAYER_DELETE");edit(s,"beta","description","又改了称号");awaitDeniedUnchanged(s,changedTitleDeletion,s.snapshot(),"title modification after deletion blocks restoration");
    }
    JsonObject report=new JsonObject();report.addProperty("result","PASS");report.addProperty("checks",checks.size());report.add("details",JSON.toJsonTree(checks));Files.writeString(Path.of("build/reports/undo-integration.json"),JSON.toJson(report));System.out.println("MEMORIALTITLES_UNDO_PASS "+checks.size());
  }
  private static void awaitDeniedUnchanged(TitleService s,long id,Snapshot before,String label)throws Exception {deny(s.undo(ADMIN,id),label);check(s.snapshot().equals(before),label+" preserves current data");}
}
