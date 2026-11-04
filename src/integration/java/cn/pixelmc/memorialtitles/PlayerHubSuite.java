package cn.pixelmc.memorialtitles;

import static cn.pixelmc.memorialtitles.domain.Model.*;

import cn.pixelmc.memorialtitles.api.PlayerHubApi;
import cn.pixelmc.memorialtitles.domain.TitlePresentation;
import cn.pixelmc.memorialtitles.persistence.ChangeJournal;
import cn.pixelmc.memorialtitles.service.PlayerHubView;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Pure contract checks; the SQL suite separately verifies committed mutation/undo behavior. */
public final class PlayerHubSuite {
  private static final List<String> checks = new ArrayList<>();
  private static void check(boolean valid, String label) { if (!valid) throw new AssertionError(label); checks.add(label); System.out.println("PASS " + label); }
  private static void deny(Runnable job, String label) { try { job.run(); throw new AssertionError(label); } catch (IllegalArgumentException | IllegalStateException expected) { check(true, label); } }
  private static Title title(String id, boolean visible, boolean issuable) { return new Title(id, RichText.plain("山海" + id), "说明", "来源", "获取说明只作描述", "纪念", 0, 319, visible, issuable, 4, 123); }
  private static Snapshot state(Map<String, Title> titles, UUID player, UUID other, Map<Key, Owned> owned) {
    return new Snapshot(titles, Map.of(player, new Profile(player,"Player",1,2),other,new Profile(other,"Private",1,2)), owned,
        Map.of(player,"retired"), Set.of(other), Map.of(), Settings.defaults(), Map.of());
  }
  public static void main(String[] args) throws Exception {
    Title legacy = title("legacy",true,true);
    JsonObject oldJson = JSON.toJsonTree(legacy).getAsJsonObject(); oldJson.remove("presentation");
    Title decoded = JSON.fromJson(oldJson,Title.class);
    check(decoded.presentation().equals(Presentation.defaults()) && decoded.icon()==319, "old JSON defaults to leaf/none/slate without changing CustomModelData");
    Presentation selected = new Presentation("personal","treasured","jade");
    Title styled = new Title(legacy.id(),legacy.name(),legacy.description(),legacy.source(),legacy.acquisition(),legacy.category(),legacy.order(),legacy.icon(),true,true,8,123,selected);
    check(styled.revised(9).created(222).presentation().equals(selected) && JSON.fromJson(JSON.toJson(styled),Title.class).equals(styled), "revision creation and JSON roundtrip retain presentation and rich text");
    check(TitlePresentation.resolve(Presentation.defaults()).rarityLabel().isEmpty() && TitlePresentation.resolve(selected).rarityLabel().equals("珍藏"), "legacy rarity has no invented label and canonical rarity resolves correctly");
    deny(() -> new Presentation("achievement","none","slate"), "unknown badge rejects validation");
    deny(() -> new Presentation("leaf","legendary","neon"), "unknown rarity/theme rejects validation");
    JsonObject invalidJson=JSON.toJsonTree(legacy).getAsJsonObject();invalidJson.getAsJsonObject("presentation").addProperty("theme","neon");
    deny(() -> JSON.fromJson(invalidJson,Title.class), "JSON metadata errors remain direct safe validation errors for admin HTTP400");
    UUID player=UUID.randomUUID(), other=UUID.randomUUID();
    Map<String,Title> titles=new HashMap<>(); titles.put("public",title("public",true,true)); titles.put("secret",title("secret",false,true)); titles.put("retired",title("retired",false,false));
    Owned mine=new Owned(player,"retired",1234,false,false), theirs=new Owned(other,"secret",999,true,true);
    Snapshot state=state(titles,player,other,Map.of(new Key(player,"retired"),mine,new Key(other,"secret"),theirs));
    JsonObject view=PlayerHubView.page(state,player,null,20); JsonArray rows=view.getAsJsonArray("titles");
    check(rows.size()==2 && rows.asList().stream().noneMatch(row->row.getAsJsonObject().get("id").getAsString().equals("secret")), "catalogue excludes hidden definitions owned only by another player");
    JsonObject retired=rows.asList().stream().map(JsonElement::getAsJsonObject).filter(row->row.get("id").getAsString().equals("retired")).findFirst().orElseThrow();
    check(retired.get("owned").getAsBoolean() && !retired.get("issuable").getAsBoolean() && !retired.get("publicCollection").getAsBoolean() && !retired.get("publicDisplay").getAsBoolean(), "private retired owned title is retained with exact own privacy");
    check(view.keySet().equals(Set.of("revision","equippedTitle","displayPreferences","globalDisplay","titles","nextCursor")) && !JSON.toJson(view).contains(other.toString()) && !view.has("settings"), "safe response has exact keys and no admin players receipts directory or backend settings");
    check(retired.getAsJsonObject("palette").keySet().equals(Set.of("accent","light","rarityColor","rarityLabel")), "snapshot supplies canonical resolved palette without Hub guesses");
    check(view.get("equippedTitle").getAsString().equals("retired"), "hidden public display does not pretend the title is unequipped");
    for(int i=0;i<53;i++) titles.put("page"+i,title("page"+i,true,true));
    state=state(titles,player,other,state.owned()); Set<String> visited=new HashSet<>(); String cursor=null; int pages=0;
    do { JsonObject page=PlayerHubView.page(state,player,cursor,20); for(JsonElement row:page.getAsJsonArray("titles")) check(visited.add(row.getAsJsonObject().get("id").getAsString()),"page row unique "+row.getAsJsonObject().get("id").getAsString()); cursor=page.get("nextCursor").isJsonNull()?null:page.get("nextCursor").getAsString();pages++; } while(cursor!=null);
    check(visited.size()==55 && pages==3,"revision-bound paging visits every visible row without truncation");
    JsonObject first=PlayerHubView.page(state,player,null,20); String stale=first.get("nextCursor").getAsString();
    titles.put("new",title("new",true,true)); Snapshot changed=state(titles,player,other,state.owned());
    deny(()->PlayerHubView.page(changed,player,stale,20),"changed catalogue rejects old cursor rather than mixing revisions");
    Map<String,Title> longTitles=new HashMap<>();
    for(int i=0;i<100;i++)longTitles.put("long"+i,new Title("long"+i,RichText.plain("长称号"),"长".repeat(2048),"来".repeat(256),"获".repeat(512),"类".repeat(128),0,0,true,true,1));
    JsonObject bounded=PlayerHubView.page(state(longTitles,player,other,Map.of()),player,null,100);
    check(JSON.toJson(bounded).getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=PlayerHubView.MAX_PAGE_BYTES && !bounded.get("nextCursor").isJsonNull(),"large UTF-8 catalogue pages stop at450KiB with explicit continuation");
    Snapshot before=state(Map.of("legacy",legacy),player,other,Map.of()), after=state(Map.of("legacy",styled),player,other,Map.of());
    Set<String> fields=new HashSet<>(); ChangeJournal.diff(before,after).forEach(d->fields.add(d.key()));
    check(fields.equals(Set.of("T|legacy|presentation.badge","T|legacy|presentation.rarity","T|legacy|presentation.theme")), "selective undo records independent badge rarity and theme fields");
    deny(()->PlayerHubView.validate("snapshot",JsonParser.parseString("{\"player\":\"spoof\"}").getAsJsonObject()),"payload cannot choose another player");
    deny(()->PlayerHubView.validate("visibility",JsonParser.parseString("{\"visible\":\"false\",\"expectedRevision\":\"r\"}").getAsJsonObject()),"mutation booleans are explicit typed values");
    deny(()->PlayerHubView.validate("equip",JsonParser.parseString("{\"title\":\"retired\"}").getAsJsonObject()),"mutations require expected revision");
    deny(()->PlayerHubView.validate("catalogue",JsonParser.parseString("{\"cursor\":\"x\",\"limit\":101}").getAsJsonObject()),"page size cannot bypass bounded limit");
    deny(()->PlayerHubView.validate("admin",new JsonObject()),"unknown administrative action is rejected");
    check(PlayerHubApi.protocolVersion()==1 && PlayerHubApi.request(player,"snapshot","{}").isCompletedExceptionally(),"optional boundary advertises protocol1 and rejects absent runtime");
    Path report=Path.of("build/reports/player-hub-contract.json");Files.createDirectories(report.getParent());Files.writeString(report,JSON.toJson(Map.of("passed",true,"checks",checks)));
  }
}
