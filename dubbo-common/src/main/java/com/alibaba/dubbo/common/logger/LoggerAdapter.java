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
package com.alibaba.dubbo.common.logger;

import com.alibaba.dubbo.common.extension.SPI;

import java.io.File;

/**
 * Logger provider
 * <p>
 * Logger 适配器接口
 */
@SPI
public interface LoggerAdapter {

    /**
     * Get a logger
     *
     * @param key the returned logger will be named after clazz
     * @return logger
     */
    Logger getLogger(Class<?> key);

    /**
     * Get a logger
     *
     * @param key the returned logger will be named after key
     * @return logger
     */
    Logger getLogger(String key);

    /**
     * Get the current logging level
     *
     * @return current logging level
     */
    Level getLevel();

    /**
     * Set the current logging level
     *
     * @param level logging level
     */
    void setLevel(Level level);

    /**
     * Get the current logging file
     *
     * @return current logging file
     */
    File getFile();

    /**
     * Set the current logging file
     *
     * @param file logging file
     */
    void setFile(File file);
}