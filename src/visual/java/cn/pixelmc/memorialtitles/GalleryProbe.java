package cn.pixelmc.memorialtitles;

import cn.pixelmc.memorialtitles.client.*;
import cn.pixelmc.memorialtitles.forge.Network;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Screenshot-only driver. Not included in any release JAR; only runs with -Pgallery. */
@Mod.EventBusSubscriber(modid=MemorialTitles.ID,value=Dist.CLIENT)
public final class GalleryProbe {
  private record Step(String name,Runnable action,BooleanSupplier ready,int delay) {}
  private static final List<Step> STEPS=new ArrayList<>();
  private static final UUID ALEX=UUID.fromString("f0063548-23c3-41f9-8e39-42801f495193");
  private static int phase,index;
  private static long since;
  private static boolean acted;
  private static String hoverTitle="";
  private static RemotePlayer previewPlayer;
  private static CompletableFuture<Void> language;
  private static Minecraft mc(){return Minecraft.getInstance();}
  private static void add(String name,Runnable action,BooleanSupplier ready){STEPS.add(new Step(name,action,ready,1200));}
  private static boolean admin(){return mc().screen instanceof AdminScreen;}
  private static boolean chest(){return mc().screen instanceof ContainerScreen;}
  private static boolean hasButton(String label){return mc().screen!=null&&mc().screen.children().stream().anyMatch(w->w instanceof Button b&&b.getMessage().getString().equals(label));}
  private static boolean hasTitleRow(){return mc().screen instanceof AdminScreen&&mc().screen.children().stream().anyMatch(w->w instanceof Theme.Action b&&b.detail.contains("runtime_title"));}
  private static void click(String label){
    if(mc().screen==null)throw new IllegalStateException("No screen for "+label);
    for(var child:List.copyOf(mc().screen.children()))if(child instanceof Button b&&b.getMessage().getString().equals(label)){b.onPress();return;}
    throw new IllegalStateException("Button missing: "+label);
  }
  private static void editTitle(){
    for(var child:List.copyOf(mc().screen.children()))if(child instanceof Theme.Action b&&b.detail.contains("runtime_title")){b.onPress();return;}
    throw new IllegalStateException("Runtime title missing");
  }
  private static void setField(String label,String value){
    for(var child:mc().screen.children())if(child instanceof EditBox box&&box.getMessage().getString().equals(label)){box.setValue(value);return;}
    throw new IllegalStateException("Field missing: "+label);
  }
  private static void close(){hoverTitle="";if(mc().player!=null)mc().player.closeContainer();mc().setScreen(null);}
  private static void open(String mode,UUID target){close();JsonObject j=new JsonObject();j.addProperty("mode",mode);if(target!=null)j.addProperty("target",target.toString());Network.request("open",j);}
  private static void build(){
    add("01-admin-titles",()->Network.request("adminOpen",new JsonObject()),GalleryProbe::hasTitleRow);
    add("02-admin-basic",GalleryProbe::editTitle,()->hasButton("基本资料"));
    add("03-admin-source-icon",()->click("来源 / 图标"),GalleryProbe::admin);
    add("04-admin-text-styles",()->click("文字样式"),GalleryProbe::admin);
    add("05-admin-rgb-picker",()->click("打开选色器"),()->mc().screen instanceof ColorPickerScreen);
    add("06-admin-gradient",()->{click("取消");click("渐变选区");},GalleryProbe::admin);
    add("07-admin-player-selection",()->{click("放弃修改");click("玩家收藏");},()->hasButton("选择本页玩家"));
    add("08-admin-batch-controls",()->{click("选择本页玩家");setField("目标称号 ID","launch_memory");},GalleryProbe::admin);
    add("09-admin-player-profile",()->click("查看档案"),()->hasButton("向上翻阅"));
    add("10-admin-display-reward",()->click("展示与奖励"),()->hasButton("展示 / 首登奖励"));
    add("11-admin-position-backup",()->click("位置 / 备份"),GalleryProbe::admin);
    add("12-admin-audit-history",()->click("操作历史"),()->hasButton("向上翻阅"));
    add("13-admin-data-management",()->click("数据管理"),()->hasButton("查看数据库状态"));
    add("14-admin-database-status",()->click("查看数据库状态"),()->hasButton("向上翻阅"));
    add("15-player-own-collection",()->open("collection",null),GalleryProbe::chest);
    add("16-player-title-lore-privacy",()->hoverTitle="runtime_title",GalleryProbe::chest);
    add("17-player-catalogue",()->open("catalogue",null),GalleryProbe::chest);
    add("18-player-unowned-title",()->hoverTitle="sea_visitor",GalleryProbe::chest);
    add("19-player-public-players",()->open("people",null),GalleryProbe::chest);
    add("20-player-public-collection",()->open("collection",ALEX),GalleryProbe::chest);
    add("21-player-public-hidden-title-lore",()->hoverTitle="runtime_title",GalleryProbe::chest);
    add("22-player-search",()->{
      hoverTitle="";
      mc().gameMode.handleInventoryMouseClick(mc().player.containerMenu.containerId,49,0,ClickType.PICKUP,mc().player);
    },()->mc().screen instanceof SearchScreen);
    add("23-world-tab-prefix",()->{close();mc().options.keyPlayerList.setDown(true);},()->mc().player!=null&&mc().screen==null);
    add("24-world-chat-prefix",()->{
      mc().options.keyPlayerList.setDown(false);mc().player.connection.sendChat("新的旅程，欢迎一起出发！");mc().setScreen(new ChatScreen(""));
    },()->mc().screen instanceof ChatScreen);
    add("25-world-head-title",()->{close();spawnPreview();},()->mc().screen==null&&previewPlayer!=null);
  }
  @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event)throws Exception{
    if(!Boolean.getBoolean("mt.gallery")||event.phase!=TickEvent.Phase.END)return;
    Minecraft m=mc();long now=System.currentTimeMillis();
    if(phase==0&&m.screen instanceof AccessibilityOnboardingScreen){m.screen.onClose();return;}
    if(previewPlayer!=null&&m.player!=null){var text=ClientRuntime.WORN.get(m.player.getUUID());if(text!=null)ClientRuntime.WORN.put(previewPlayer.getUUID(),text);}
    if(phase==0&&m.screen instanceof TitleScreen&&m.getOverlay()==null){
      m.options.guiScale().set(2);m.resizeDisplay();m.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
      m.options.languageCode="zh_cn";m.getLanguageManager().setSelected("zh_cn");language=m.reloadResourcePacks();phase=1;return;
    }
    if(phase==1&&language.isDone()&&m.getOverlay()==null){
      ConnectScreen.startConnecting(new TitleScreen(),m,new ServerAddress("127.0.0.1",45567),new ServerData("MemorialTitles screenshots","127.0.0.1:45567",false),false);
      phase=2;since=now;return;
    }
    if(phase==2){
      if(m.player!=null&&ClientRuntime.WORN.containsKey(m.player.getUUID())&&now-since>11000){build();phase=3;acted=false;}
      else if(now-since>90000)throw new IllegalStateException("Screenshot login timeout");
      return;
    }
    if(phase!=3)return;
    if(index>=STEPS.size()){LogUtils.getLogger().info("MEMORIALTITLES_GALLERY_PASS screenshots={}",STEPS.size());phase=4;m.stop();return;}
    Step step=STEPS.get(index);
    if(!acted){step.action.run();since=now;acted=true;return;}
    positionPointer();
    if(now-since>step.delay&&step.ready.getAsBoolean()){
      Path folder=Path.of("../docs/screenshots/gallery-20261023");Files.createDirectories(folder);
      Path file=folder.resolve(step.name+".png");
      try(var pixels=Screenshot.takeScreenshot(m.getMainRenderTarget())){pixels.writeToFile(file);}
      LogUtils.getLogger().info("MT_GALLERY_SCREENSHOT {}",file.toAbsolutePath());index++;acted=false;
    }else if(now-since>15000)throw new IllegalStateException("Screenshot step timeout: "+step.name);
  }
  private static void positionPointer()throws ReflectiveOperationException{
    var m=mc();if(m.screen==null)return;
    double x=4,y=4;
    if(!hoverTitle.isEmpty()&&m.screen instanceof ContainerScreen&&m.player!=null){
      for(var slot:m.player.containerMenu.slots){var stack=slot.getItem();if(stack.hasTag()&&hoverTitle.equals(stack.getTag().getString("MemorialTitleId"))){
        x=(m.screen.width-176)/2+slot.x+8;y=(m.screen.height-222)/2+slot.y+8;break;
      }}
    }
    var mouseX=MouseHandler.class.getDeclaredField("xpos");mouseX.setAccessible(true);
    var mouseY=MouseHandler.class.getDeclaredField("ypos");mouseY.setAccessible(true);
    mouseX.setDouble(m.mouseHandler,x*m.getWindow().getScreenWidth()/m.screen.width);
    mouseY.setDouble(m.mouseHandler,y*m.getWindow().getScreenHeight()/m.screen.height);
  }
  private static void spawnPreview(){
    var m=mc();UUID id=UUID.fromString("167c6df2-47a1-42f8-8b75-754232f1bf9d");
    previewPlayer=new RemotePlayer(m.level,new GameProfile(id,"TitlePreview"));
    previewPlayer.moveTo(m.player.getX(),m.player.getY(),m.player.getZ()+5,180,0);previewPlayer.setNoGravity(true);m.level.addPlayer(9001,previewPlayer);
    m.player.setYRot(0);m.player.yRotO=0;m.player.setXRot(0);m.player.xRotO=0;
    ClientRuntime.WORN.put(id,ClientRuntime.WORN.get(m.player.getUUID()));
  }
}
