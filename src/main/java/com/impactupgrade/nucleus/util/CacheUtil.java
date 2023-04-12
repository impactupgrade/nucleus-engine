package com.impactupgrade.nucleus.util;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class CacheUtil {

  public static <K, V> LoadingCache<K, V> buildLoadingCache(Function<K, V> loader) {
    return CacheBuilder.newBuilder()
        .refreshAfterWrite(15, TimeUnit.MINUTES)
        .build(CacheLoader.asyncReloading(
            new CacheLoader<>() {
              @Override
              public V load(K k) throws Exception {
                return loader.apply(k);
              }
            },
            Executors.newSingleThreadExecutor()
        ));
  }

  public static <K, V> Cache<K, V> buildManualCache() {
    return CacheBuilder.newBuilder()
        .expireAfterWrite(15, TimeUnit.MINUTES)
        .build();
  }
}
