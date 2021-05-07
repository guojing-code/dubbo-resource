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
package com.alibaba.dubbo.common.serialize.support;

import java.util.Collection;

/**
 * This class can be replaced with the contents in config file, but for now I think the class is easier to write
 * <p>
 * 这个类可以替换为配置文件中的内容，但是现在我认为这个类更容易编写。
 * <p>
 * 序列化优化器接口，业务可以实现该接口将需要序列化的类，注册到kryo
 */
public interface SerializationOptimizer {

    /**
     * @return 需要使用优化的类的集合
     */
    Collection<Class> getSerializableClasses();

}