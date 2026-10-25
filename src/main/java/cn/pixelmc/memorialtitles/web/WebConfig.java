package cn.pixelmc.memorialtitles.web;

import java.net.URI;
import java.nio.file.*;
import java.util.*;
import static cn.pixelmc.memorialtitles.domain.Model.JSON;

public record WebConfig(boolean enabled, String bind, int port, String publicUrl, int sessionMinutes, List<String> extraOrigins) {
  public WebConfig {
    if (bind == null || bind.isBlank() || port < 1024 || port > 65535 || sessionMinutes < 5 || sessionMinutes > 1440) throw new IllegalArgumentException("网页绑定地址、端口或会话时长无效");
    publicUrl=publicUrl==null?"":publicUrl;
    URI url = URI.create(publicUrl);
    if (!publicUrl.isEmpty() && (!Set.of("http","https").contains(url.getScheme()) || url.getHost()==null || url.getUserInfo()!=null || (url.getPath()!=null && !url.getPath().isEmpty() && !url.getPath().equals("/")) || url.getQuery()!=null || url.getFragment()!=null)) throw new IllegalArgumentException("publicUrl 必须是网页服务根地址，直接使用IP时可留空");
    publicUrl=publicUrl.replaceAll("/$","");
    extraOrigins=extraOrigins==null?List.of():List.copyOf(extraOrigins);
    for(String value:extraOrigins){URI u=URI.create(value);if(u.getHost()==null||!Set.of("http","https").contains(u.getScheme()))throw new IllegalArgumentException("额外来源无效");}
  }
  public static WebConfig load(Path folder) throws Exception {
    Path path=folder.resolve("web.json");
    if(!Files.exists(path)) Files.writeString(path,JSON.toJson(new WebConfig(true,"0.0.0.0",18765,"",480,List.of())));
    return Objects.requireNonNull(JSON.fromJson(Files.readString(path),WebConfig.class));
  }
}
