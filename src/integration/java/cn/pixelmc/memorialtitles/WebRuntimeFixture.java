package cn.pixelmc.memorialtitles;

import static cn.pixelmc.memorialtitles.domain.Model.*;
import cn.pixelmc.memorialtitles.persistence.Database;
import cn.pixelmc.memorialtitles.service.*;
import cn.pixelmc.memorialtitles.forge.ServerFiles;
import cn.pixelmc.memorialtitles.web.*;
import java.nio.file.*;
import java.sql.Connection;
import java.util.*;

public final class WebRuntimeFixture {
  public static void main(String[] args)throws Exception{
    String password=System.getenv("MT_TEST_PASSWORD");if(password==null)throw new IllegalStateException("Set MT_TEST_PASSWORD");
    var root=new Database(new Database.Config("jdbc:mariadb://127.0.0.1:43321/","root",password));
    try(Connection c=root.open()){Database.execute(c,"CREATE DATABASE IF NOT EXISTS mt_web_runtime CHARACTER SET utf8mb4");}
    Path run=Path.of("run-web-runtime").toAbsolutePath(),config=run.resolve("config/memorialtitles");Files.createDirectories(config);
    var database=new Database.Config("jdbc:mariadb://127.0.0.1:43321/mt_web_runtime","root",password);UUID player=UUID.fromString("7520aa16-9d82-324e-acf4-7ecbf7aa436d");var console=TitleService.Actor.consoleActor();
    try(TitleService s=new TitleService(new Database(database))){s.reconnect().get();s.login(player,"MTTester").get();s.authorize(console,player,true).get();
      s.saveTitle(console,new Title("runtime_title",RichText.plain("网页同步前"),"Web runtime verification","local","console","test",0,0,true,true,0),s.snapshot().titles().containsKey("runtime_title")?s.snapshot().titles().get("runtime_title").revision():0).get();s.settings(console,Settings.defaults()).get();s.grant(console,List.of(player),"runtime_title","runtime").get();s.equip(player,"runtime_title").get();}
    Files.writeString(config.resolve("server.json"),JSON.toJson(new ServerFiles.Config(database,run.resolve("backups").toString())));
    Files.writeString(config.resolve("web.json"),JSON.toJson(new WebConfig(true,"127.0.0.1",18782,"http://127.0.0.1:18782",60,List.of())));
    AccountStore accounts=new AccountStore(config);Path credentials=Path.of("tools/web-runtime.local.json");
    if(accounts.get("runtime_admin")==null){String initial=AccountStore.randomPassword();accounts.create("runtime_admin",player,initial);Files.writeString(credentials,JSON.toJson(Map.of("username","runtime_admin","password",initial,"url","http://127.0.0.1:18782")));}
    System.out.println("WEB_RUNTIME_FIXTURE_READY");
  }
}
