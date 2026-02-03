package com.evoila.janus.security.config;

import java.util.List;
import java.util.stream.Collectors;
import lombok.Data;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@Data
public class OAuthToken {
  private String preferredUsername;
  private String email;
  private List<String> groups;

  public List<GrantedAuthority> getAuthorities() {
    return groups.stream()
        .map(group -> new SimpleGrantedAuthority("ROLE_" + group))
        .collect(Collectors.toList());
  }
}
