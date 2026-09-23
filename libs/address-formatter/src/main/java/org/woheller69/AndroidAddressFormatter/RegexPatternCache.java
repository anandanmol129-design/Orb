package org.woheller69.AndroidAddressFormatter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

class RegexPatternCache {
  private final Map<String, Pattern> map = new ConcurrentHashMap<>();

  public Pattern get(String key) {
    return map.computeIfAbsent(key, Pattern::compile);
  }

  public Pattern get(String key, int flags) {
    return map.computeIfAbsent(key, k -> Pattern.compile(k, flags));
  }
}
