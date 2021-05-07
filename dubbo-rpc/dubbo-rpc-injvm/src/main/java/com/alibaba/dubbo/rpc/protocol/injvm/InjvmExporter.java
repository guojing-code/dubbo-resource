/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.rpc.protocol.injvm;

import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.protocol.AbstractExporter;

import java.util.Map;

/**
 * InjvmExporter
 * <p>
 * Injvm Exporter 实现类
 */
class InjvmExporter<T> extends AbstractExporter<T> {

    /**
     * 服务键
     */
    private final String key;

    /**
     * Exporter 集合
     * <p>
     * key: 服务键
     * <p>
     * 该值实际就是 {@link com.alibaba.dubbo.rpc.protocol.AbstractProtocol#exporterMap}
     */
    private final Map<String, Exporter<?>> exporterMap;

    InjvmExporter(Invoker<T> invoker, String key, Map<String, Exporter<?>> exporterMap) {
        super(invoker);
        this.key = key;
        this.exporterMap = exporterMap;
        // 添加到 Exporter 集合
        exporterMap.put(key, this);
    }

    @Override
    public void unexport() {
        super.unexport();
        // 移除出 Exporter 集合
        exporterMap.remove(key);
    }

}