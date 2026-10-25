package cn.pixelmc.memorialtitles.service;

import static cn.pixelmc.memorialtitles.domain.Model.*;
import java.util.*;

/** Map<Key,...> must never be serialized as stringified Java record keys. */
public record SnapshotTransfer(List<Title> titles,List<Profile> players,List<Owned> owned,
    Map<UUID,String> equipped,Set<UUID> admins,List<Receipt> receipts,Settings settings,
    Map<UUID,DisplayPreferences> displayPreferences) {
  public static SnapshotTransfer of(Snapshot s){return new SnapshotTransfer(List.copyOf(s.titles().values()),List.copyOf(s.players().values()),List.copyOf(s.owned().values()),s.equipped(),s.admins(),List.copyOf(s.receipts().values()),s.settings(),s.displayPreferences());}
  public Snapshot snapshot(){Map<String,Title> ts=new HashMap<>();Map<UUID,Profile> ps=new HashMap<>();Map<Key,Owned> os=new HashMap<>();Map<Key,Receipt> rs=new HashMap<>();
    for(Title t:titles)if(ts.put(t.id(),t)!=null)throw new IllegalArgumentException("重复称号");
    for(Profile p:players)if(ps.put(p.id(),p)!=null)throw new IllegalArgumentException("重复玩家");
    for(Owned o:owned)if(os.put(new Key(o.player(),o.title()),o)!=null)throw new IllegalArgumentException("重复收藏");
    for(Receipt r:receipts)if(rs.put(new Key(r.player(),r.title()),r)!=null)throw new IllegalArgumentException("重复领取");
    return new Snapshot(ts,ps,os,equipped,admins,rs,settings,displayPreferences);
  }
}
