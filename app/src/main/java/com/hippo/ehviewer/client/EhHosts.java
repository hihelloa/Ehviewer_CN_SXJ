/*
 * Copyright 2018 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.client;

/*
 * Created by Hippo on 2018/3/23.
 */

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.Hosts;
import com.hippo.ehviewer.Settings;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.dnsoverhttps.DnsOverHttps;


public class EhHosts implements Dns {

    private static final Map<String, List<InetAddress>> builtInHosts;

    static {
        Map<String, List<InetAddress>> map = new HashMap<>();
        if (Settings.getBuiltInHosts()) {
            put(map, "e-hentai.org",
                    "104.20.18.168",
                    "104.20.19.168",
                    "172.66.132.196",
                    "172.66.140.62",
                    "104.20.18.168",
                    "172.67.2.238"
//                    "194.126.173.115"
            );
            put(map, "repo.e-hentai.org",   "104.20.18.168",
                    "104.20.19.168",
                    "172.67.2.238");
            put(map, "forums.e-hentai.org",   "172.66.132.196",
                    "172.66.140.62");
            put(map, "upld.e-hentai.org", "89.149.221.236", "95.211.208.236");
            put(map, "ehgt.org",
//                    "109.236.85.28",
//                    "62.112.8.21",
//                    "89.39.106.43",
                    "185.132.132.79",
                    "62.112.8.152",
                    "185.132.132.76",
                    "2a00:7c80:0:123::3a85",
                    "2a00:7c80:0:12d::38a1",
                    "2a00:7c80:0:13b::37a4");
//            put(map, "ehgt.org",
//                    "109.236.85.28",
//                    "62.112.8.21",
//                    "89.39.106.43");
            put(map, "raw.githubusercontent.com", "151.101.0.133", "151.101.64.133", "151.101.128.133", "151.101.192.133");

        }

        if (Settings.getBuiltEXHosts()) {
            put(map, "exhentai.org",
                    "178.175.128.251",
                    "178.175.128.252",
                    "178.175.128.253",
                    "178.175.128.254",
                    "178.175.129.251",
                    "178.175.129.252",
                    "178.175.129.253",
                    "178.175.129.254",
                    "178.175.132.19",
                    "178.175.132.20",
                    "178.175.132.21",
                    "178.175.132.22"
            );
            put(map, "upld.exhentai.org", "178.175.132.22", "178.175.129.254", "178.175.128.254");
            put(map, "s.exhentai.org",
//                    "178.175.129.251",
//                    "178.175.129.252",
                    "178.175.129.253",
                    "178.175.129.254",
//                    "178.175.128.251",
//                    "178.175.128.252",
                    "178.175.128.253",
                    "178.175.128.254",
//                    "178.175.132.19",
//                    "178.175.132.20",
                    "178.175.132.21",
                    "178.175.132.22"
            );
        }

        builtInHosts = map;
    }

    private final Hosts hosts;
    private static DnsOverHttps dnsOverHttps;

    // DNS 结果内存缓存。原来每次新建连接都要查一次 SQLite（hosts.getList），
    // 没命中再造一次系统 DNS / DoH。H@H 每页图片的域名都不一样，
    // 一本漫画下来几百次解析，单次几百毫秒就直接体现在"看图慢、下载慢"上。
    private static final long DNS_CACHE_TTL_MS = 10 * 60 * 1000L;
    private static final int DNS_CACHE_MAX = 512;
    private static final Map<String, DnsCacheEntry> DNS_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<String, DnsCacheEntry>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, DnsCacheEntry> eldest) {
                    return size() > DNS_CACHE_MAX;
                }
            });

    private static final class DnsCacheEntry {
        final List<InetAddress> addresses;
        final long expireAt;

        DnsCacheEntry(List<InetAddress> addresses, long expireAt) {
            this.addresses = addresses;
            this.expireAt = expireAt;
        }
    }

    public EhHosts(Context context) {
        hosts = EhApplication.getHosts(context);
        DnsOverHttps.Builder builder = new DnsOverHttps.Builder()
                .client(new OkHttpClient.Builder().cache(EhApplication.getOkHttpCache(context)).build())
                .url(HttpUrl.get("https://77.88.8.1/dns-query"));
        dnsOverHttps = builder.post(true).build();
    }

    private static void put(Map<String, List<InetAddress>> map, String host, String... ips) {
        List<InetAddress> addresses = new ArrayList<>();
        for (String ip : ips) {
            addresses.add(Hosts.toInetAddress(host, ip));
        }
        map.put(host, addresses);
    }


    @NonNull
    @Override
    public List<InetAddress> lookup(@NonNull String hostname) throws UnknownHostException {
        List<InetAddress> cached = getFromCache(hostname);
        if (cached != null) {
            return cached;
        }
        List<InetAddress> result = lookupFromSource(hostname);
        putToCache(hostname, result);
        return result;
    }

    @Nullable
    private List<InetAddress> getFromCache(String hostname) {
        DnsCacheEntry entry = DNS_CACHE.get(hostname);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() > entry.expireAt) {
            DNS_CACHE.remove(hostname);
            return null;
        }
        return new ArrayList<>(entry.addresses);
    }

    private void putToCache(String hostname, List<InetAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return;
        }
        DNS_CACHE.put(hostname, new DnsCacheEntry(new ArrayList<>(addresses),
                System.currentTimeMillis() + DNS_CACHE_TTL_MS));
    }

    @NonNull
    private List<InetAddress> lookupFromSource(@NonNull String hostname) throws UnknownHostException {
        List<InetAddress> inetAddresses = hosts.getList(hostname);
        if (inetAddresses != null && !inetAddresses.isEmpty()) {
            Collections.shuffle(inetAddresses, new Random(System.currentTimeMillis()));
            return inetAddresses;
        }
        if (Settings.getBuiltInHosts() || Settings.getBuiltEXHosts()) {
            inetAddresses = builtInHosts.get(hostname);
            if (inetAddresses != null) {
                // 复制一份再打乱：builtInHosts 是静态共享的，
                // 并发下载时多个 worker 同时 shuffle 同一个 ArrayList 会越界。
                inetAddresses = new ArrayList<>(inetAddresses);
                Collections.shuffle(inetAddresses, new Random(System.currentTimeMillis()));
                return inetAddresses;
            }
        }
        if (Settings.getDoH()) {
            inetAddresses = dnsOverHttps.lookup(hostname);
            if (!inetAddresses.isEmpty()) {
                Collections.shuffle(inetAddresses, new Random(System.currentTimeMillis()));
                return inetAddresses;
            }
        }
        try {
            inetAddresses = Arrays.asList(InetAddress.getAllByName(hostname));
            Collections.shuffle(inetAddresses, new Random(System.currentTimeMillis()));
            return inetAddresses;
        } catch (NullPointerException e) {
            UnknownHostException unknownHostException =
                    new UnknownHostException("Broken system behaviour for dns lookup of " + hostname);
            unknownHostException.initCause(e);
            throw unknownHostException;
        }
    }
}
