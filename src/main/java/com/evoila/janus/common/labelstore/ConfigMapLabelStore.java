package com.evoila.janus.common.labelstore;

import com.evoila.janus.common.enforcement.utils.LabelPatternUtils;
import com.evoila.janus.security.config.OAuthToken;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * ConfigMap-based label store implementation Handles both admin access and service-specific label
 * constraints
 */
@Slf4j
@Component
@Getter
public class ConfigMapLabelStore implements LabelStore {

  private static final String LABELS_KEY = "labels";
  private static final String ADMIN_KEY = "admin";
  private static final String HEADER_KEY = "header";

  @Value("${label-store.type:config}")
  private String labelStoreType;

  @Value("${label-store.configmap.path:${LABEL_STORE_CONFIGMAP_PATH:./local-configmap.yaml}}")
  private String configMapPath;

  @Value("${label-store.configmap.watch-enabled:true}")
  private boolean watchEnabled;

  @Value("${label-store.configmap.watch-interval:30}")
  private int watchIntervalSeconds;

  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private final ConfigMapParser configMapParser = new ConfigMapParser();
  private final ConfigMapWatcher configMapWatcher = new ConfigMapWatcher();

  private long lastModified = 0;
  private final AtomicReference<Map<String, Object>> configData =
      new AtomicReference<>(new HashMap<>());
  private final AtomicReference<ConfigurationData> configurationData =
      new AtomicReference<>(new ConfigurationData());

  @PostConstruct
  public void initialize() {
    if ("configmap".equalsIgnoreCase(labelStoreType)) {
      loadConfigMap();
      if (watchEnabled) {
        configMapWatcher.startWatching(this);
      }
    } else {
      log.info("Using config-based label store (not ConfigMap)");
    }
  }

  private void loadConfigMap() {
    try {
      if (!shouldLoadConfigMap()) {
        return;
      }

      updateLastModified();
      String content = readConfigMapContent();
      Map<String, Object> parsed = parseYamlContent(content);
      configData.set(parsed);

      if (parsed != null) {
        ConfigurationData newConfig = configMapParser.parseConfiguration(parsed);
        configurationData.set(newConfig);
        log.info(
            "Successfully loaded ConfigMap with {} services and admin config",
            newConfig.getServiceConstraints().size());
      }

    } catch (IOException e) {
      log.error("Error loading ConfigMap from {}: {}", configMapPath, e.getMessage());
    } catch (Exception e) {
      log.error(
          "Error parsing ConfigMap configuration from {}: {}", configMapPath, e.getMessage(), e);
    }
  }

  private boolean shouldLoadConfigMap() {
    if (configMapPath == null) {
      log.warn("ConfigMap path is null, skipping load");
      return false;
    }

    Path path = Paths.get(configMapPath);
    if (!Files.exists(path)) {
      log.warn("ConfigMap file not found: {}", configMapPath);
      return false;
    }

    long currentModified = getLastModifiedTime(path);
    return currentModified > lastModified;
  }

  private long getLastModifiedTime(Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (IOException e) {
      log.warn("Could not get last modified time for {}: {}", path, e.getMessage());
      return 0;
    }
  }

  private void updateLastModified() {
    Path path = Paths.get(configMapPath);
    lastModified = getLastModifiedTime(path);
  }

  private String readConfigMapContent() throws IOException {
    Path path = Paths.get(configMapPath);
    String content = Files.readString(path);
    log.debug("Loading ConfigMap from: {}", configMapPath);
    return content;
  }

  private Map<String, Object> parseYamlContent(String content) {
    Yaml yaml = new Yaml();
    Map<String, Object> parsed = yaml.load(content);

    if (parsed == null) {
      log.warn("ConfigMap file is empty or invalid: {}", configMapPath);
      return new HashMap<>();
    }

    return parsed;
  }

  @Override
  public boolean hasClusterWideAccess(OAuthToken token) {
    List<String> groups = getGroups(token);

    return hasAdminGroup(groups) || hasAdminConfiguration(groups);
  }

  private List<String> getGroups(OAuthToken token) {
    return token.getGroups() != null ? token.getGroups() : Collections.emptyList();
  }

  private boolean hasAdminGroup(List<String> groups) {
    for (String group : groups) {
      if (ADMIN_KEY.equalsIgnoreCase(group)) {
        log.debug("User has cluster-wide access via admin group '{}'", group);
        return true;
      }
    }
    return false;
  }

  private boolean hasAdminConfiguration(List<String> groups) {
    if (!configurationData.get().getAdminConfig().isEmpty() && groups.contains(ADMIN_KEY)) {
      log.debug("User has cluster-wide access via admin configuration");
      return true;
    }
    return false;
  }

  @Override
  public Map<String, Set<String>> getServiceLabelConstraints(OAuthToken token, String serviceName) {
    String username = token.getPreferredUsername();
    List<String> groups = getGroups(token);

    if (hasClusterWideAccess(token)) {
      return createAdminConstraints(username, serviceName);
    }

    return getUserServiceConstraints(username, groups, serviceName);
  }

  private Map<String, Set<String>> createAdminConstraints(String username, String serviceName) {
    log.debug(
        "Admin user '{}' has cluster-wide access - returning wildcard constraints for service '{}'",
        username,
        serviceName);
    Map<String, Set<String>> adminConstraints = new HashMap<>();
    adminConstraints.put(LABELS_KEY, Set.of("*"));
    adminConstraints.put("service", Set.of("*"));
    adminConstraints.put("namespace", Set.of("*"));
    adminConstraints.put("job", Set.of("*"));
    return adminConstraints;
  }

  private Map<String, Set<String>> getUserServiceConstraints(
      String username, List<String> groups, String serviceName) {
    Map<String, Map<String, List<String>>> serviceConstraint =
        configurationData.get().getServiceConstraints().get(serviceName);

    if (serviceConstraint == null) {
      throw new SecurityException("Service not configured: " + serviceName);
    }

    Map<String, Set<String>> mergedConstraints = new HashMap<>();
    mergeUserConstraints(mergedConstraints, serviceConstraint, username);
    mergeGroupConstraints(mergedConstraints, serviceConstraint, groups);

    if (mergedConstraints.isEmpty()) {
      throw new SecurityException(
          "No access configured for user: " + username + " in service: " + serviceName);
    }

    log.debug(
        "Service '{}' label constraints for user '{}' with groups {}: {}",
        serviceName,
        username,
        groups,
        mergedConstraints);
    return mergedConstraints;
  }

  private void mergeUserConstraints(
      Map<String, Set<String>> mergedConstraints,
      Map<String, Map<String, List<String>>> serviceConstraint,
      String username) {
    Map<String, List<String>> userConstraints = serviceConstraint.get(username);
    if (userConstraints != null) {
      mergeConstraints(mergedConstraints, userConstraints);
    }
  }

  private void mergeGroupConstraints(
      Map<String, Set<String>> mergedConstraints,
      Map<String, Map<String, List<String>>> serviceConstraint,
      List<String> groups) {
    for (String group : groups) {
      Map<String, List<String>> groupConstraints = serviceConstraint.get(group);
      if (groupConstraints != null) {
        mergeConstraints(mergedConstraints, groupConstraints);
      }
    }
  }

  private void mergeConstraints(Map<String, Set<String>> target, Map<String, List<String>> source) {
    source.forEach(
        (key, values) -> {
          if (LABELS_KEY.equals(key)) {
            // Special handling for labels - process exclusions and add wildcard if needed
            Set<String> allowedLabels = target.computeIfAbsent(key, k -> new HashSet<>());
            processLabelsSection(allowedLabels, values);

            // If no explicit labels were added (only exclusions), add wildcard
            if (allowedLabels.isEmpty()) {
              allowedLabels.add("*");
            }
          } else {
            // Normal handling for other constraint types
            target.computeIfAbsent(key, k -> new HashSet<>()).addAll(values);
          }
        });
  }

  @Override
  public boolean hasLabelAccess(OAuthToken token, String labelName) {
    Set<String> allowedLabels = getAllowedLabels(token);

    if (allowedLabels.contains(LabelPatternUtils.WILDCARD_ASTERISK)) {
      return true;
    }

    boolean hasAccess = allowedLabels.contains(labelName);
    log.debug(
        "User '{}' access to label '{}': {}", token.getPreferredUsername(), labelName, hasAccess);
    return hasAccess;
  }

  @Override
  public Set<String> getAllowedLabels(OAuthToken token) {
    String username = token.getPreferredUsername();
    List<String> groups = getGroups(token);

    Set<String> allowedLabels = new HashSet<>();
    collectLabelsFromServices(allowedLabels, username, groups);

    if (allowedLabels.isEmpty()) {
      allowedLabels.add("*");
    }

    log.debug("User '{}' with groups {} has access to labels: {}", username, groups, allowedLabels);
    return allowedLabels;
  }

  private void collectLabelsFromServices(
      Set<String> allowedLabels, String username, List<String> groups) {
    for (Map<String, Map<String, List<String>>> serviceConstraint :
        configurationData.get().getServiceConstraints().values()) {
      collectUserLabels(allowedLabels, serviceConstraint, username);
      collectGroupLabels(allowedLabels, serviceConstraint, groups);
    }
  }

  private void collectUserLabels(
      Set<String> allowedLabels,
      Map<String, Map<String, List<String>>> serviceConstraint,
      String username) {
    Map<String, List<String>> userConstraints = serviceConstraint.get(username);
    if (userConstraints != null && userConstraints.containsKey(LABELS_KEY)) {
      processLabelsSection(allowedLabels, userConstraints.get(LABELS_KEY));
    }
  }

  private void collectGroupLabels(
      Set<String> allowedLabels,
      Map<String, Map<String, List<String>>> serviceConstraint,
      List<String> groups) {
    for (String group : groups) {
      Map<String, List<String>> groupConstraints = serviceConstraint.get(group);
      if (groupConstraints != null && groupConstraints.containsKey(LABELS_KEY)) {
        processLabelsSection(allowedLabels, groupConstraints.get(LABELS_KEY));
      }
    }
  }

  @Override
  public Set<String> getExcludedLabels(OAuthToken token) {
    String username = token.getPreferredUsername();
    List<String> groups = getGroups(token);

    Set<String> excludedLabels = new HashSet<>();
    collectExclusionsFromServices(excludedLabels, username, groups);

    log.info(
        "EXCLUSION SUMMARY: User '{}' with groups {} has excluded labels: {}",
        username,
        groups,
        excludedLabels);
    return excludedLabels;
  }

  private void collectExclusionsFromServices(
      Set<String> excludedLabels, String username, List<String> groups) {
    for (Map<String, Map<String, List<String>>> serviceConstraint :
        configurationData.get().getServiceConstraints().values()) {
      collectUserExclusions(excludedLabels, serviceConstraint, username);
      collectGroupExclusions(excludedLabels, serviceConstraint, groups);
    }
  }

  private void collectUserExclusions(
      Set<String> excludedLabels,
      Map<String, Map<String, List<String>>> serviceConstraint,
      String username) {
    Map<String, List<String>> userConstraints = serviceConstraint.get(username);
    if (userConstraints != null && userConstraints.containsKey(LABELS_KEY)) {
      processExclusionsOnly(excludedLabels, userConstraints.get(LABELS_KEY));
    }
  }

  private void collectGroupExclusions(
      Set<String> excludedLabels,
      Map<String, Map<String, List<String>>> serviceConstraint,
      List<String> groups) {
    for (String group : groups) {
      Map<String, List<String>> groupConstraints = serviceConstraint.get(group);
      if (groupConstraints != null && groupConstraints.containsKey(LABELS_KEY)) {
        processExclusionsOnly(excludedLabels, groupConstraints.get(LABELS_KEY));
      }
    }
  }

  private void processExclusionsOnly(Set<String> excludedLabels, List<String> labels) {
    for (String label : labels) {
      if (label.startsWith("!=")) {
        String labelName = label.substring(2).trim();
        excludedLabels.add(labelName);
        log.info(
            "EXCLUSION COLLECTED: ConfigMapLabelStore collected excluded label '{}'", labelName);
      }
    }
  }

  /**
   * Processes labels from the labels section, handling !=labelname syntax by removing those labels.
   *
   * @param allowedLabels The set of allowed labels to modify
   * @param labels The list of labels from the configuration
   */
  private void processLabelsSection(Set<String> allowedLabels, List<String> labels) {
    for (String label : labels) {
      if (label.startsWith("!=")) {
        // Don't add exclusions to allowedLabels - they're handled separately via
        // getExcludedLabels()
        log.debug("ConfigMapLabelStore: Skipping exclusion '{}' in allowedLabels", label);
      } else {
        // Add normal labels to allowed set
        allowedLabels.add(label);
        log.debug("ConfigMapLabelStore: Added label '{}' to allowed labels", label);
      }
    }
  }

  @PreDestroy
  public void stopWatching() {
    configMapWatcher.stopWatching(scheduler);
  }

  public boolean isInitialized() {
    return "configmap".equalsIgnoreCase(labelStoreType) && !configData.get().isEmpty();
  }

  public int getConstraintCount() {
    return configurationData.get().getServiceConstraints().size();
  }

  @Override
  public Map<String, String> getTenantHeaders(OAuthToken token, String serviceName) {
    List<String> groups = getGroups(token);

    if (hasClusterWideAccess(token) && !configurationData.get().getAdminTenantHeaders().isEmpty()) {
      log.debug(
          "Admin tenant headers for service '{}': {}",
          serviceName,
          configurationData.get().getAdminTenantHeaders());
      return new HashMap<>(configurationData.get().getAdminTenantHeaders());
    }

    return getGroupTenantHeaders(groups, serviceName);
  }

  private Map<String, String> getGroupTenantHeaders(List<String> groups, String serviceName) {
    Map<String, Map<String, String>> serviceTenantHeaders =
        configurationData.get().getTenantHeaderConstraints().get(serviceName);
    if (serviceTenantHeaders == null) {
      log.debug("No tenant headers configured for service: {}", serviceName);
      return new HashMap<>();
    }

    Map<String, String> combinedHeaders =
        configMapParser.collectGroupHeaders(groups, serviceTenantHeaders, serviceName);

    if (!combinedHeaders.isEmpty()) {
      log.debug("Combined tenant headers for service '{}': {}", serviceName, combinedHeaders);
    } else {
      log.debug("No tenant headers found for any groups in service '{}'", serviceName);
    }

    return combinedHeaders;
  }

  /** Configuration data holder class */
  @Getter
  private static class ConfigurationData {
    private final Map<String, List<String>> adminConfig = new HashMap<>();
    private final Map<String, String> adminTenantHeaders = new HashMap<>();
    private final Map<String, Map<String, Map<String, List<String>>>> serviceConstraints =
        new HashMap<>();
    private final Map<String, Map<String, Map<String, String>>> tenantHeaderConstraints =
        new HashMap<>();

    public void clear() {
      adminConfig.clear();
      adminTenantHeaders.clear();
      serviceConstraints.clear();
      tenantHeaderConstraints.clear();
    }
  }

  /** ConfigMap parser class to handle YAML parsing logic */
  @Slf4j
  private static class ConfigMapParser {

    public ConfigurationData parseConfiguration(Map<String, Object> configData) {
      ConfigurationData data = new ConfigurationData();

      parseAdminConfiguration(configData, data);
      parseServiceConfigurations(configData, data);

      return data;
    }

    private void parseAdminConfiguration(Map<String, Object> configData, ConfigurationData data) {
      if (!configData.containsKey(ADMIN_KEY)) {
        return;
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> adminData = (Map<String, Object>) configData.get(ADMIN_KEY);
      data.getAdminConfig().putAll(convertToListMap(adminData));
      log.debug("Loaded admin configuration: {}", data.getAdminConfig().keySet());

      parseAdminTenantHeaders(adminData, data);
    }

    private void parseAdminTenantHeaders(Map<String, Object> adminData, ConfigurationData data) {
      if (!adminData.containsKey(HEADER_KEY)) {
        return;
      }

      Object headerData = adminData.get(HEADER_KEY);
      if (!(headerData instanceof List)) {
        return;
      }

      @SuppressWarnings("unchecked")
      List<Object> headerList = (List<Object>) headerData;

      for (Object headerItem : headerList) {
        parseAdminHeaderItem(headerItem, data);
      }

      log.debug("Loaded admin tenant headers: {}", data.getAdminTenantHeaders());
    }

    private void parseAdminHeaderItem(Object headerItem, ConfigurationData data) {
      if (headerItem instanceof String headerString) {
        parseStringHeader(headerString, data);
      } else if (headerItem instanceof Map) {
        parseMapHeader((Map<String, Object>) headerItem, data);
      }
    }

    private void parseStringHeader(String headerString, ConfigurationData data) {
      int colonIndex = headerString.indexOf(':');
      if (colonIndex > 0) {
        String headerName = headerString.substring(0, colonIndex).trim();
        String headerValue = headerString.substring(colonIndex + 1).trim();

        // Check if header already exists and combine values with pipe separator
        Map<String, String> adminHeaders = data.getAdminTenantHeaders();
        if (adminHeaders.containsKey(headerName)) {
          combineHeaderValues(adminHeaders, headerName, headerValue);
        } else {
          adminHeaders.put(headerName, headerValue);
        }
      }
    }

    private void parseMapHeader(Map<String, Object> headerMap, ConfigurationData data) {
      for (Map.Entry<String, Object> entry : headerMap.entrySet()) {
        String headerName = entry.getKey();
        String headerValue = entry.getValue() != null ? entry.getValue().toString() : "";

        // Check if header already exists and combine values with pipe separator
        Map<String, String> adminHeaders = data.getAdminTenantHeaders();
        if (adminHeaders.containsKey(headerName)) {
          combineHeaderValues(adminHeaders, headerName, headerValue);
        } else {
          adminHeaders.put(headerName, headerValue);
        }
      }
    }

    private void parseServiceConfigurations(
        Map<String, Object> configData, ConfigurationData data) {
      for (Map.Entry<String, Object> entry : configData.entrySet()) {
        String serviceName = entry.getKey();

        if (ADMIN_KEY.equals(serviceName)) {
          continue;
        }

        parseServiceConfiguration(serviceName, entry.getValue(), data);
      }
    }

    private void parseServiceConfiguration(
        String serviceName, Object serviceData, ConfigurationData data) {
      if (!(serviceData instanceof Map)) {
        return;
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> serviceMap = (Map<String, Object>) serviceData;

      parseServiceLabelConstraints(serviceName, serviceMap, data);
      parseServiceTenantHeaders(serviceName, serviceMap, data);
    }

    private void parseServiceLabelConstraints(
        String serviceName, Map<String, Object> serviceMap, ConfigurationData data) {
      if (!serviceMap.containsKey("user-label-constraints")) {
        return;
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> userConstraintsData =
          (Map<String, Object>) serviceMap.get("user-label-constraints");

      Map<String, Map<String, List<String>>> userConstraints =
          convertToNestedListMap(userConstraintsData);
      data.getServiceConstraints().put(serviceName, userConstraints);

      log.debug(
          "Loaded constraints for service {}: {} users/groups",
          serviceName,
          userConstraints.size());
    }

    private void parseServiceTenantHeaders(
        String serviceName, Map<String, Object> serviceMap, ConfigurationData data) {
      if (!serviceMap.containsKey("tenant-header-constraints")) {
        return;
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> tenantHeaderData =
          (Map<String, Object>) serviceMap.get("tenant-header-constraints");

      Map<String, Map<String, String>> tenantHeaders = convertToStringMap(tenantHeaderData);
      data.getTenantHeaderConstraints().put(serviceName, tenantHeaders);

      log.debug(
          "Loaded tenant headers for service {}: {} users/groups",
          serviceName,
          tenantHeaders.size());
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> convertToListMap(Map<String, Object> data) {
      Map<String, List<String>> result = new HashMap<>();
      for (Map.Entry<String, Object> entry : data.entrySet()) {
        if (entry.getValue() instanceof List) {
          List<Object> rawList = (List<Object>) entry.getValue();
          List<String> stringList = new ArrayList<>();
          for (Object item : rawList) {
            if (item instanceof String string) {
              // Process value to strip ~ prefix for explicit regex patterns
              String processedValue = LabelPatternUtils.processConfigValue(string);
              stringList.add(processedValue);

              if (!string.equals(processedValue)) {
                log.debug("Processed explicit regex value: '{}' -> '{}'", string, processedValue);
              }
            }
          }
          result.put(entry.getKey(), stringList);
        }
      }
      return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, List<String>>> convertToNestedListMap(
        Map<String, Object> data) {
      Map<String, Map<String, List<String>>> result = new HashMap<>();
      for (Map.Entry<String, Object> entry : data.entrySet()) {
        if (entry.getValue() instanceof Map) {
          Map<String, Object> innerMap = (Map<String, Object>) entry.getValue();
          result.put(entry.getKey(), convertToListMap(innerMap));
        }
      }
      return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, String>> convertToStringMap(Map<String, Object> data) {
      Map<String, Map<String, String>> result = new HashMap<>();
      for (Map.Entry<String, Object> entry : data.entrySet()) {
        if (entry.getValue() instanceof Map) {
          Map<String, Object> innerMap = (Map<String, Object>) entry.getValue();
          Map<String, String> stringMap = new HashMap<>();

          for (Map.Entry<String, Object> innerEntry : innerMap.entrySet()) {
            if (innerEntry.getValue() instanceof List && HEADER_KEY.equals(innerEntry.getKey())) {
              parseHeaderList((List<Object>) innerEntry.getValue(), stringMap);
            }
          }
          result.put(entry.getKey(), stringMap);
        }
      }
      return result;
    }

    private void parseHeaderList(List<Object> headerList, Map<String, String> stringMap) {
      for (Object headerItem : headerList) {
        if (headerItem instanceof String headerString) {
          parseHeaderString(headerString, stringMap);
        } else if (headerItem instanceof Map) {
          parseHeaderMap((Map<String, Object>) headerItem, stringMap);
        }
      }
    }

    private void parseHeaderString(String headerString, Map<String, String> stringMap) {
      int colonIndex = headerString.indexOf(':');
      if (colonIndex > 0) {
        String headerName = headerString.substring(0, colonIndex).trim();
        String headerValue = headerString.substring(colonIndex + 1).trim();

        // Check if header already exists and combine values with pipe separator
        if (stringMap.containsKey(headerName)) {
          combineHeaderValues(stringMap, headerName, headerValue);
        } else {
          stringMap.put(headerName, headerValue);
        }
      }
    }

    private void parseHeaderMap(Map<String, Object> headerMap, Map<String, String> stringMap) {
      for (Map.Entry<String, Object> mapEntry : headerMap.entrySet()) {
        String headerName = mapEntry.getKey();
        String headerValue = mapEntry.getValue() != null ? mapEntry.getValue().toString() : "";

        // Check if header already exists and combine values with pipe separator
        if (stringMap.containsKey(headerName)) {
          combineHeaderValues(stringMap, headerName, headerValue);
        } else {
          stringMap.put(headerName, headerValue);
        }
      }
    }

    private Map<String, String> collectGroupHeaders(
        List<String> groups,
        Map<String, Map<String, String>> serviceTenantHeaders,
        String serviceName) {
      Map<String, String> combinedHeaders = new HashMap<>();

      for (String group : groups) {
        Map<String, String> groupHeaders = serviceTenantHeaders.get(group);
        if (groupHeaders != null && !groupHeaders.isEmpty()) {
          log.debug(
              "Found group-based tenant headers for group '{}' in service '{}': {}",
              group,
              serviceName,
              groupHeaders);
          combineGroupHeaders(combinedHeaders, groupHeaders);
        }
      }

      return combinedHeaders;
    }

    private void combineGroupHeaders(
        Map<String, String> combinedHeaders, Map<String, String> groupHeaders) {
      for (Map.Entry<String, String> entry : groupHeaders.entrySet()) {
        String headerName = entry.getKey();
        String headerValue = entry.getValue();

        if (combinedHeaders.containsKey(headerName)) {
          combineHeaderValues(combinedHeaders, headerName, headerValue);
        } else {
          combinedHeaders.put(headerName, headerValue);
        }
      }
    }

    private void combineHeaderValues(
        Map<String, String> combinedHeaders, String headerName, String headerValue) {
      String existingValue = combinedHeaders.get(headerName);

      if (!existingValue.equals(headerValue)) {
        String combinedValue = existingValue + "|" + headerValue;
        combinedHeaders.put(headerName, combinedValue);
        log.debug(
            "Combined header '{}': '{}' + '{}' = '{}'",
            headerName,
            existingValue,
            headerValue,
            combinedValue);
      } else {
        log.debug("Skipping duplicate header '{}' with same value '{}'", headerName, headerValue);
      }
    }
  }

  /** ConfigMap watcher class to handle file watching logic */
  @Slf4j
  private static class ConfigMapWatcher {

    public void startWatching(ConfigMapLabelStore labelStore) {
      labelStore.scheduler.scheduleWithFixedDelay(
          () -> {
            try {
              labelStore.loadConfigMap();
            } catch (Exception e) {
              log.error("Error during ConfigMap watch: {}", e.getMessage());
            }
          },
          labelStore.watchIntervalSeconds,
          labelStore.watchIntervalSeconds,
          TimeUnit.SECONDS);

      log.info("Started watching for ConfigMap changes: {}", labelStore.configMapPath);
    }

    public void stopWatching(ScheduledExecutorService scheduler) {
      scheduler.shutdown();
      try {
        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
          scheduler.shutdownNow();
        }
      } catch (InterruptedException _) {
        scheduler.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }
}
