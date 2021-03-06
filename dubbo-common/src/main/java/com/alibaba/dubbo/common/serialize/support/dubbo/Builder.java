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
package com.alibaba.dubbo.common.serialize.support.dubbo;

import com.alibaba.dubbo.common.bytecode.ClassGenerator;
import com.alibaba.dubbo.common.io.UnsafeByteArrayInputStream;
import com.alibaba.dubbo.common.io.UnsafeByteArrayOutputStream;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.serialize.support.java.CompactedObjectInputStream;
import com.alibaba.dubbo.common.serialize.support.java.CompactedObjectOutputStream;
import com.alibaba.dubbo.common.utils.ClassHelper;
import com.alibaba.dubbo.common.utils.IOUtils;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.common.utils.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;

/**
 * Builder.
 *
 * @param <T> type.
 */

/**
 * ??????????????????????????????
 *
 * @param <T> ??????
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public abstract class Builder<T> implements GenericDataFlags {

    /**
     * ?????? Serializable ??? Builder ??????????????? Java ??????????????????????????????
     * <p>
     * ??????????????? Throwable ????????????????????? transient ?????????????????? Serializable ?????????
     */
    static final Builder<Serializable> SerializableBuilder = new Builder<Serializable>() {

        @Override
        public Class<Serializable> getType() {
            return Serializable.class;
        }

        @Override
        public void writeTo(Serializable obj, GenericObjectOutput out) throws IOException {
            // NULL ????????? OBJECT_NULL ??? mBuffer ???
            if (obj == null) {
                out.write0(OBJECT_NULL);
                // ??????
            } else {
                // ?????? OBJECT_STREAM ??? mBuffer ???
                out.write0(OBJECT_STREAM);
                // ?????? compactjava ?????????????????????????????????
                UnsafeByteArrayOutputStream bos = new UnsafeByteArrayOutputStream();
                CompactedObjectOutputStream oos = new CompactedObjectOutputStream(bos);
                oos.writeObject(obj);
                oos.flush();
                bos.close();
                byte[] b = bos.toByteArray();
                // ?????? Length( ?????????????????? ) ??? mBuffer ???
                out.writeUInt(b.length);
                // ?????? ???????????? ??? mBuffer ???
                out.write0(b, 0, b.length);
            }
        }

        @Override
        public Serializable parseFrom(GenericObjectInput in) throws IOException {
            // ??????????????????
            byte b = in.read0();
            // NULL ????????? null
            if (b == OBJECT_NULL) {
                return null;
            }
            if (b != OBJECT_STREAM) {
                throw new IOException("Input format error, expect OBJECT_NULL|OBJECT_STREAM, get " + b + ".");
            }

            // ?????? compactjava ????????????????????????????????????
            UnsafeByteArrayInputStream bis = new UnsafeByteArrayInputStream(in.read0(in.readUInt()));
            CompactedObjectInputStream ois = new CompactedObjectInputStream(bis);
            try {
                return (Serializable) ois.readObject();
            } catch (ClassNotFoundException e) {
                throw new IOException(StringUtils.toString(e));
            }
        }

    };

    private static final AtomicLong BUILDER_CLASS_COUNTER = new AtomicLong(0);

    private static final String BUILDER_CLASS_NAME = Builder.class.getName();

    /**
     * ?????? Serializable ??????????????? Builder ????????????
     */
    private static final Map<Class<?>, Builder<?>> BuilderMap = new ConcurrentHashMap<Class<?>, Builder<?>>();

    /**
     * ????????? Serializable ??????????????? Builder ????????????
     */
    private static final Map<Class<?>, Builder<?>> nonSerializableBuilderMap = new ConcurrentHashMap<Class<?>, Builder<?>>();

    private static final String FIELD_CONFIG_SUFFIX = ".fc";

    private static final int MAX_FIELD_CONFIG_FILE_SIZE = 16 * 1024;

    /**
     * Field ????????????
     */
    private static final Comparator<String> FNC = new Comparator<String>() {

        @Override
        public int compare(String n1, String n2) {
            return compareFieldName(n1, n2);
        }

    };

    /**
     * Field ??????????????? {@link #FNC} ????????????
     */
    private static final Comparator<Field> FC = new Comparator<Field>() {

        @Override
        public int compare(Field f1, Field f2) {
            return compareFieldName(f1.getName(), f2.getName());
        }

    };

    /**
     * ?????????????????????
     */
    private static final Comparator<Constructor> CC = new Comparator<Constructor>() {
        public int compare(Constructor o1, Constructor o2) {
            return o1.getParameterTypes().length - o2.getParameterTypes().length;
        }
    };

    // class-descriptor mapper

    /**
     * ???????????????
     */
    private static final List<String> mDescList = new ArrayList<String>();

    /**
     * ???????????????
     */
    private static final Map<String, Integer> mDescMap = new ConcurrentHashMap<String, Integer>();

    /**
     * ClassDescriptorMapper ???????????????
     */
    public static ClassDescriptorMapper DEFAULT_CLASS_DESCRIPTOR_MAPPER = new ClassDescriptorMapper() {

        @Override
        public String getDescriptor(int index) {
            if (index < 0 || index >= mDescList.size()) {
                return null;
            }
            return mDescList.get(index);
        }

        @Override
        public int getDescriptorIndex(String desc) {
            Integer ret = mDescMap.get(desc);
            return ret == null ? -1 : ret;
        }

    };

    // Must be protected. by qian.lei
    protected static Logger logger = LoggerFactory.getLogger(Builder.class);

    /**
     * ????????????( Array ) ??? Builder ??????
     */
    static final Builder<Object[]> GenericArrayBuilder = new AbstractObjectBuilder<Object[]>() {

        @Override
        public Class<Object[]> getType() {
            return Object[].class;
        }

        @Override
        protected Object[] newInstance(GenericObjectInput in) throws IOException {
            // ??????????????????????????????????????????
            return new Object[in.readUInt()];
        }

        @Override
        protected void readObject(Object[] ret, GenericObjectInput in) throws IOException {
            // ??????????????????????????? ret ???
            for (int i = 0; i < ret.length; i++) {
                ret[i] = in.readObject();
            }
        }

        @Override
        protected void writeObject(Object[] obj, GenericObjectOutput out) throws IOException {
            // ?????? Length( ???????????? ) ??? mBuffer
            out.writeUInt(obj.length);
            // ??????????????????????????? mBuffer ???
            for (Object item : obj) {
                out.writeObject(item);
            }
        }

    };

    /**
     * ?????? Object ??? Builder ??????
     */
    static final Builder<Object> GenericBuilder = new Builder<Object>() {

        @Override
        public Class<Object> getType() {
            return Object.class;
        }

        @Override
        public void writeTo(Object obj, GenericObjectOutput out) throws IOException {
            out.writeObject(obj);
        }

        @Override
        public Object parseFrom(GenericObjectInput in) throws IOException {
            return in.readObject();
        }

    };

    static {
        addDesc(boolean[].class);
        addDesc(byte[].class);
        addDesc(char[].class);
        addDesc(short[].class);
        addDesc(int[].class);
        addDesc(long[].class);
        addDesc(float[].class);
        addDesc(double[].class);

        addDesc(Boolean.class);
        addDesc(Byte.class);
        addDesc(Character.class);
        addDesc(Short.class);
        addDesc(Integer.class);
        addDesc(Long.class);
        addDesc(Float.class);
        addDesc(Double.class);

        addDesc(String.class);
        addDesc(String[].class);

        addDesc(ArrayList.class);
        addDesc(HashMap.class);
        addDesc(HashSet.class);
        addDesc(Date.class);
        addDesc(java.sql.Date.class);
        addDesc(java.sql.Time.class);
        addDesc(java.sql.Timestamp.class);
        addDesc(java.util.LinkedList.class);
        addDesc(java.util.LinkedHashMap.class);
        addDesc(java.util.LinkedHashSet.class);

        register(byte[].class, new Builder<byte[]>() {
            @Override
            public Class<byte[]> getType() {
                return byte[].class;
            }

            @Override
            public void writeTo(byte[] obj, GenericObjectOutput out) throws IOException {
                out.writeBytes(obj);
            }

            @Override
            public byte[] parseFrom(GenericObjectInput in) throws IOException {
                return in.readBytes();
            }
        });
        register(Boolean.class, new Builder<Boolean>() {
            @Override
            public Class<Boolean> getType() {
                return Boolean.class;
            }

            @Override
            public void writeTo(Boolean obj, GenericObjectOutput out) throws IOException {
                if (obj == null)
                    out.write0(VARINT_N1);
                else if (obj.booleanValue())
                    out.write0(VARINT_1);
                else
                    out.write0(VARINT_0);
            }

            @Override
            public Boolean parseFrom(GenericObjectInput in) throws IOException {
                byte b = in.read0();
                switch (b) {
                    case VARINT_N1:
                        return null;
                    case VARINT_0:
                        return Boolean.FALSE;
                    case VARINT_1:
                        return Boolean.TRUE;
                    default:
                        throw new IOException("Input format error, expect VARINT_N1|VARINT_0|VARINT_1, get " + b + ".");
                }
            }
        });
        register(Byte.class, new Builder<Byte>() {
            @Override
            public Class<Byte> getType() {
                return Byte.class;
            }

            @Override
            public void writeTo(Byte obj, GenericObjectOutput out) throws IOException {
                if (obj == null) {
                    out.write0(OBJECT_NULL);
                } else {
                    out.write0(OBJECT_VALUE);
                    out.writeByte(obj.byteValue());
                }
            }

            @Override
            public Byte parseFrom(GenericObjectInput in) throws IOException {
                byte b = in.read0();
                if (b == OBJECT_NULL)
                    return null;
                if (b != OBJECT_VALUE)
                    throw new IOException("Input format error, expect OBJECT_NULL|OBJECT_VALUE, get " + b + ".");

                return Byte.valueOf(in.readByte());
            }
        });
        register(Character.class, new Builder<Character>() {
            @Override
            public Class<Character> getType() {
                return Character.class;
            }

            @Override
            public void writeTo(Character obj, GenericObjectOutput out) throws IOException {
                if (obj == null) {
                    out.write0(OBJECT_NULL);
                } else {
                    out.write0(OBJECT_VALUE);
                    out.writeShort((short) obj.charValue());
                }
            }

            @Override
            public Character parseFrom(GenericObjectInput in) throws IOException {
                byte b = in.read0();
                if (b == OBJECT_NULL)
                    return null;
                if (b != OBJECT_VALUE)
                    throw new IOException("Input format error, expect OBJECT_NULL|OBJECT_VALUE, get " + b + ".");

                return Character.valueOf((char) in.readShort());
            }
        });
        register(Short.class, new Builder<Short>() {
            @Override
            public Class<Short> getType() {
                return Short.class;
            }

            @Override
            public void writeTo(Short obj, GenericObjectOutput out) throws IOException {
                if (obj == null) {
                    out.write0(OBJECT_NULL);
                } else {
                    out.write0(OBJECT_VALUE);
                    out.writeShort(obj.shortValue());
                }
            }

            @Override
            public Short parseFrom(GenericObjectInput in) throws IOException {
                byte b = in.read0();
                if (b == OBJECT_NULL)
                    return null;
                if (b != OBJECT_VALUE)
                    throw new IOException("Input format error, expect OBJECT_NULL|OBJECT_VALUE, get " + b + ".");

                return Short.valueOf(in.readShort());
            }
        });
        register(Integer.class, new Builder<Integer>() {
            @Override
            public Class<Integer> getType() {
                return Integer.class;
            }

            @Override
            public void writeTo(Integer obj, GenericObjectOutput out) throws IOException {
                if (obj == null) {
                    out.write0(OBJECT_NULL);
                } else {
                    out.write0(OBJECT_VALUE);
                    out.writeInt(obj.intValue());
                }
            }

            @Override
            public Integer parseFrom(GenericObjectInput in) throws IOException {
                byte b = in.read0();
                if (b == OBJECT_NULL)
                    return null;
                if (b != OBJECT_VALUE)
                    throw new IOException("Input format error, expect OBJECT_NULL|OBJECT_VALUE, get " + b + ".");

                return Integer.valueOf(in.readInt());
            }
        });
        register(Long.class, new Builder<Long>() {
            @Override
            public Class<Long> getType() {
                return Long.class;
            }

            @Override
            public void writeTo(Long obj, GenericObjectOutput out) throws IOException {
                if (obj == null) {
                    out.write0(OBJECT_NULL);
                } else {
                    out.write0(OBJECT_VALUE);
                    out.writeLong(obj.longValue());
                }
            }

            @Override
            public Long parseFrom(GenericObjectInput in) throws IOException {
                byte b = in.read0();
                if (b == OBJECT_NULL)
                    return null;
                if (b != OBJECT_VALUE)
                    throw new IOException("Input format error, expect OBJECT_NULL|OBJECT_VALUE, get " + b + ".");

                return Long.valueOf(in.readLong());
            }
        });
        register(Float.class, new Builder<Float>() {
            @Override
            public Class<Float> getType() {
                return Float.class;
            }

            @Override
            public void writeTo(Float obj, GenericObjectOutput out) throws IOException {
                if (obj == null) {
                    out.write0(OBJECT_NULL);
                } else {
                    out.write0(OBJECT_VALUE);
                    out.writeFloat(obj.floatValue());
                }
            }

            @Override
            public Float parseFrom(GenericObjectInput in) throws IOException {
                byte b = in.read0();
                if (b == OBJECT_NULL)
                    return null;
                if (b != OBJECT_VALUE)
                    throw new IOException("Input format error, expect OBJECT_NULL|OBJECT_VALUE, get " + b + ".");

                return new Float(in.readFloat());
            }
        });
        register(Double.class, new Builder<Double>() {
            @Override
            public Class<Double> getType() {
                return Double.class;
            }

            @Override
            public void writeTo(Double obj, GenericObjectOutput out) throws IOException {
                if (obj == null) {
                    out.write0(OBJECT_NULL);
                } else {
                    out.write0(OBJECT_VALUE);
                    out.writeDouble(obj.doubleValue());
                }
            }

            @Override
            public Double parseFrom(GenericObjectInput in) throws IOException {
                byte b = in.read0();
                if (b == OBJECT_NULL)
                    return null;
                if (b != OBJECT_VALUE)
                    throw new IOException("Input format error, expect OBJECT_NULL|OBJECT_VALUE, get " + b + ".");

                return new Double(in.readDouble());
            }
        });
        register(String.class, new Builder<String>() {
            @Override
            public Class<String> getType() {
                return String.class;
            }

            @Override
            public String parseFrom(GenericObjectInput in) throws IOException {
                return in.readUTF();
            }

            @Override
            public void writeTo(String obj, GenericObjectOutput out) throws IOException {
                out.writeUTF(obj);
            }
        });
        register(StringBuilder.class, new Builder<StringBuilder>() {
            @Override
            public Class<StringBuilder> getType() {
                return StringBuilder.class;
            }

            @Override
            public StringBuilder parseFrom(GenericObjectInput in) throws IOException {
                return new StringBuilder(in.readUTF());
            }

            @Override
            public void writeTo(StringBuilder obj, GenericObjectOutput out) throws IOException {
                out.writeUTF(obj.toString());
            }
        });
        register(StringBuffer.class, new Builder<StringBuffer>() {
            @Override
            public Class<StringBuffer> getType() {
                return StringBuffer.class;
            }

            @Override
            public StringBuffer parseFrom(GenericObjectInput in) throws IOException {
                return new StringBuffer(in.readUTF());
            }

            @Override
            public void writeTo(StringBuffer obj, GenericObjectOutput out) throws IOException {
                out.writeUTF(obj.toString());
            }
        });

        // java.util
        register(ArrayList.class, new Builder<ArrayList>() {
            @Override
            public Class<ArrayList> getType() {
                return ArrayList.class;
            }

            @Override
            public void writeTo(ArrayList obj, GenericObjectOutput out) throws IOException {
                if (obj == null) {
                    out.write0(OBJECT_NULL);
                } else {
                    out.write0(OBJECT_VALUES);
                    out.writeUInt(obj.size());
                    for (Object item : obj)
                        out.writeObject(item);
                }
            }

            @Override
            public ArrayList parseFrom(GenericObjectInput in) throws IOException {
                byte b = in.read0();
                if (b == OBJECT_NULL)
                    return null;
                if (b != OBJECT_VALUES)
                    throw new IOException("Input format error, expect OBJECT_NULL|OBJECT_VALUES, get " + b + ".");

                int len = in.readUInt();
                ArrayList ret = new ArrayList(len);
                for (int i = 0; i < len; i++)
                    ret.add(in.readObject());
                return ret;
            }
        });
        register(HashMap.class, new Builder<HashMap>() {

            @Override
            public Class<HashMap> getType() {
                return HashMap.class;
            }

            @Override
            public void writeTo(HashMap obj, GenericObjectOutput out) throws IOException {
                // NULL ????????? OBJECT_NULL ??? mBuffer ???
                if (obj == null) {
                    out.write0(OBJECT_NULL);
                    // HashMap ??????
                } else {
                    // ?????? OBJECT_MAP ??? mBuffer ???
                    out.write0(OBJECT_MAP);
                    // ?????? Length(Map ??????) ??? mBuffer ???
                    out.writeUInt(obj.size());
                    // ?????? KV ??? mBuffer ???
                    for (Map.Entry entry : (Set<Map.Entry>) obj.entrySet()) {
                        out.writeObject(entry.getKey());
                        out.writeObject(entry.getValue());
                    }
                }
            }

            @Override
            public HashMap parseFrom(GenericObjectInput in) throws IOException {
                // ??????????????????
                byte b = in.read0();
                // NULL ????????? null
                if (b == OBJECT_NULL) {
                    return null;
                }
                if (b != OBJECT_MAP) {
                    throw new IOException("Input format error, expect OBJECT_NULL|OBJECT_MAP, get " + b + ".");
                }

                // ?????? Length(Map ??????)
                int len = in.readUInt();
                // ???????????? KV ??? HashMap
                HashMap ret = new HashMap(len);
                for (int i = 0; i < len; i++) {
                    ret.put(in.readObject(), in.readObject());
                }
                return ret;
            }

        });
        register(HashSet.class, new Builder<HashSet>() {
            @Override
            public Class<HashSet> getType() {
                return HashSet.class;
            }

            @Override
            public void writeTo(HashSet obj, GenericObjectOutput out) throws IOException {
                if (obj == null) {
                    out.write0(OBJECT_NULL);
                } else {
                    out.write0(OBJECT_VALUES);
                    out.writeUInt(obj.size());
                    for (Object item : obj)
                        out.writeObject(item);
                }
            }

            @Override
            public HashSet parseFrom(GenericObjectInput in) throws IOException {
                byte b = in.read0();
                if (b == OBJECT_NULL)
                    return null;
                if (b != OBJECT_VALUES)
                    throw new IOException("Input format error, expect OBJECT_NULL|OBJECT_VALUES, get " + b + ".");

                int len = in.readUInt();
                HashSet ret = new HashSet(len);
                for (int i = 0; i < len; i++)
                    ret.add(in.readObject());
                return ret;
            }
        });

        register(Date.class, new Builder<Date>() {
            @Override
            public Class<Date> getType() {
                return Date.class;
            }

            @Override
            public void writeTo(Date obj, GenericObjectOutput out) throws IOException {
                if (obj == null) {
                    out.write0(OBJECT_NULL);
                } else {
                    out.write0(OBJECT_VALUE);
                    out.writeLong(obj.getTime());
                }
            }

            @Override
            public Date parseFrom(GenericObjectInput in) throws IOException {
                byte b = in.read0();
                if (b == OBJECT_NULL)
                    return null;
                if (b != OBJECT_VALUE)
                    throw new IOException("Input format error, expect OBJECT_NULL|OBJECT_VALUE, get " + b + ".");

                return new Date(in.readLong());
            }
        });

        // java.sql
        register(java.sql.Date.class, new Builder<java.sql.Date>() {
            @Override
            public Class<java.sql.Date> getType() {
                return java.sql.Date.class;
            }

            @Override
            public void writeTo(java.sql.Date obj, GenericObjectOutput out) throws IOException {
                if (obj == null) {
                    out.write0(OBJECT_NULL);
                } else {
                    out.write0(OBJECT_VALUE);
                    out.writeLong(obj.getTime());
                }
            }

            @Override
            public java.sql.Date parseFrom(GenericObjectInput in) throws IOException {
                byte b = in.read0();
                if (b == OBJECT_NULL)
                    return null;
                if (b != OBJECT_VALUE)
                    throw new IOException("Input format error, expect OBJECT_NULL|OBJECT_VALUE, get " + b + ".");

                return new java.sql.Date(in.readLong());
            }
        });
        register(java.sql.Timestamp.class, new Builder<java.sql.Timestamp>() {
            @Override
            public Class<java.sql.Timestamp> getType() {
                return java.sql.Timestamp.class;
            }

            @Override
            public void writeTo(java.sql.Timestamp obj, GenericObjectOutput out) throws IOException {
                if (obj == null) {
                    out.write0(OBJECT_NULL);
                } else {
                    out.write0(OBJECT_VALUE);
                    out.writeLong(obj.getTime());
                }
            }

            @Override
            public java.sql.Timestamp parseFrom(GenericObjectInput in) throws IOException {
                byte b = in.read0();
                if (b == OBJECT_NULL)
                    return null;
                if (b != OBJECT_VALUE)
                    throw new IOException("Input format error, expect OBJECT_NULL|OBJECT_VALUE, get " + b + ".");

                return new java.sql.Timestamp(in.readLong());
            }
        });
        register(java.sql.Time.class, new Builder<java.sql.Time>() {
            @Override
            public Class<java.sql.Time> getType() {
                return java.sql.Time.class;
            }

            @Override
            public void writeTo(java.sql.Time obj, GenericObjectOutput out) throws IOException {
                if (obj == null) {
                    out.write0(OBJECT_NULL);
                } else {
                    out.write0(OBJECT_VALUE);
                    out.writeLong(obj.getTime());
                }
            }

            @Override
            public java.sql.Time parseFrom(GenericObjectInput in) throws IOException {
                byte b = in.read0();
                if (b == OBJECT_NULL)
                    return null;
                if (b != OBJECT_VALUE)
                    throw new IOException("Input format error, expect OBJECT_NULL|OBJECT_VALUE, get " + b + ".");

                return new java.sql.Time(in.readLong());
            }
        });
    }

    protected Builder() {
    }

    /**
     * ?????????????????? Builder ??????
     *
     * @param c                      ???
     * @param isAllowNonSerializable ??????????????????????????? {@link java.io.Serializable} ??????
     * @param <T>                    ??????
     * @return Builder ??????
     */
    public static <T> Builder<T> register(Class<T> c, boolean isAllowNonSerializable) {
        // Object ??????????????????????????? GenericBuilder
        if (c == Object.class || c.isInterface()) {
            return (Builder<T>) GenericBuilder;
        }
        // Array ??????????????? GenericArrayBuilder
        if (c == Object[].class) {
            return (Builder<T>) GenericArrayBuilder;
        }

        // ?????? Builder ??????
        Builder<T> b = (Builder<T>) BuilderMap.get(c);
        if (null != b) {
            return b;
        }

        // ???????????? Serializable ??????????????????????????????????????? IllegalStateException ??????
        boolean isSerializable = Serializable.class.isAssignableFrom(c);
        if (!isAllowNonSerializable && !isSerializable) {
            throw new IllegalStateException("Serialized class " + c.getName() +
                    " must implement java.io.Serializable (dubbo codec setting: isAllowNonSerializable = false)");
        }

        // ?????? Builder ??????
        b = (Builder<T>) nonSerializableBuilderMap.get(c);
        if (null != b) {
            return b;
        }

        // ?????????????????? Javassist ??????????????? Builder ?????????????????????
        b = newBuilder(c);

        // ????????? Builder ???????????????
        if (isSerializable) {
            BuilderMap.put(c, b);
        } else {
            nonSerializableBuilderMap.put(c, b);
        }

        return b;
    }

    public static <T> Builder<T> register(Class<T> c) {
        return register(c, false);
    }

    public static <T> void register(Class<T> c, Builder<T> b) {
        if (Serializable.class.isAssignableFrom(c)) {
            BuilderMap.put(c, b);
        } else {
            nonSerializableBuilderMap.put(c, b);
        }
    }

    private static <T> Builder<T> newBuilder(Class<T> c) {
        // ???????????????????????????????????? Builder ?????????????????? RuntimeException ??????????????????????????? GenericDataInput ??? GenericDataOutput ?????????
        if (c.isPrimitive()) {
            throw new RuntimeException("Can not create builder for primitive type: " + c);
        }

        if (logger.isInfoEnabled())
            logger.info("create Builder for class: " + c);

        Builder<?> builder;
        // ?????? Array Builder ??????
        if (c.isArray()) {
            builder = newArrayBuilder(c);
            // ?????? Object Builder ??????
        } else {
            builder = newObjectBuilder(c);
        }
        return (Builder<T>) builder;
    }

    private static Builder<?> newArrayBuilder(Class<?> c) {
        // ???????????????????????? GenericArrayBuilder
        Class<?> cc = c.getComponentType();
        if (cc.isInterface()) {
            return GenericArrayBuilder;
        }

        ClassLoader cl = ClassHelper.getCallerClassLoader(Builder.class);

        // ????????????
        String cn = ReflectUtils.getName(c), ccn = ReflectUtils.getName(cc); // get class name as int[][], double[].
        // ????????? Builder ??????
        String bcn = BUILDER_CLASS_NAME + "$bc" + BUILDER_CLASS_COUNTER.getAndIncrement();

        // ??????????????????
        int ix = cn.indexOf(']');
        String s1 = cn.substring(0, ix), s2 = cn.substring(ix); // if name='int[][]' then s1='int[', s2='][]'

        // `#writeObject(T obj, GenericObjectOutput out)` ???????????????????????????????????????????????????????????????
        StringBuilder cwt = new StringBuilder("public void writeTo(Object obj, ").append(GenericObjectOutput.class.getName()).append(" out) throws java.io.IOException{"); // writeTo code.
        // `#readObject(T ret, GenericObjectInput in)` ???????????????????????????????????????????????????????????????
        StringBuilder cpf = new StringBuilder("public Object parseFrom(").append(GenericObjectInput.class.getName()).append(" in) throws java.io.IOException{"); // parseFrom code.

        // `#writeObject(T obj, GenericObjectOutput out)` ??????????????? Length + ???????????? ??????????????????
        cwt.append("if( $1 == null ){ $2.write0(OBJECT_NULL); return; }");
        cwt.append(cn).append(" v = (").append(cn).append(")$1; int len = v.length; $2.write0(OBJECT_VALUES); $2.writeUInt(len); for(int i=0;i<len;i++){ ");

        // `#readObject(T obj, GenericObjectOutput out)` ??????????????? Length + ???????????? ??????????????????
        cpf.append("byte b = $1.read0(); if( b == OBJECT_NULL ) return null; if( b != OBJECT_VALUES ) throw new java.io.IOException(\"Input format error, expect OBJECT_NULL|OBJECT_VALUES, get \" + b + \".\");");
        cpf.append("int len = $1.readUInt(); if( len == 0 ) return new ").append(s1).append('0').append(s2).append("; ");
        cpf.append(cn).append(" ret = new ").append(s1).append("len").append(s2).append("; for(int i=0;i<len;i++){ ");

        Builder<?> builder = null;
        // ????????????????????? GenericDataOutput ??? GenericDataInput ??????????????????????????????????????????
        if (cc.isPrimitive()) {
            // ????????????????????? `#writeObject(T obj, GenericObjectOutput out)` ??? `#readObject(T ret, GenericObjectInput in)` ???????????????????????????????????????
            if (cc == boolean.class) {
                cwt.append("$2.writeBool(v[i]);");
                cpf.append("ret[i] = $1.readBool();");
            } else if (cc == byte.class) {
                cwt.append("$2.writeByte(v[i]);");
                cpf.append("ret[i] = $1.readByte();");
            } else if (cc == char.class) {
                cwt.append("$2.writeShort((short)v[i]);");
                cpf.append("ret[i] = (char)$1.readShort();");
            } else if (cc == short.class) {
                cwt.append("$2.writeShort(v[i]);");
                cpf.append("ret[i] = $1.readShort();");
            } else if (cc == int.class) {
                cwt.append("$2.writeInt(v[i]);");
                cpf.append("ret[i] = $1.readInt();");
            } else if (cc == long.class) {
                cwt.append("$2.writeLong(v[i]);");
                cpf.append("ret[i] = $1.readLong();");
            } else if (cc == float.class) {
                cwt.append("$2.writeFloat(v[i]);");
                cpf.append("ret[i] = $1.readFloat();");
            } else if (cc == double.class) {
                cwt.append("$2.writeDouble(v[i]);");
                cpf.append("ret[i] = $1.readDouble();");
            }
            // ?????????????????????????????? Builder ??????
            // ????????????????????????????????? Builder ????????????????????????????????????????????????????????????????????????
        } else {
            // ????????????????????? Builder ??????
            builder = register(cc);
            // ????????????????????? `#writeObject(T obj, GenericObjectOutput out)` ??? `#readObject(T ret, GenericObjectInput in)` ???????????????????????????????????????
            cwt.append("builder.writeTo(v[i], $2);");
            cpf.append("ret[i] = (").append(ccn).append(")builder.parseFrom($1);");
        }
        // ??????????????? `}`
        cwt.append(" } }");
        cpf.append(" } return ret; }");

        // ?????? Builder ???????????????
        ClassGenerator cg = ClassGenerator.newInstance(cl);
        // ????????????
        cg.setClassName(bcn);
        // ??????????????? Builder.class
        cg.setSuperClass(Builder.class);
        // ????????????????????????
        cg.addDefaultConstructor();
        // ?????? `builders` ????????????
        if (builder != null) {
            cg.addField("public static " + BUILDER_CLASS_NAME + " builder;");
        }
        // ?????? `#getType()` ????????????????????????
        cg.addMethod("public Class getType(){ return " + cn + ".class; }");
        // ?????? `#writeObject(T obj, GenericObjectOutput out)` ????????????????????????
        cg.addMethod(cwt.toString());
        // ?????? `#readObject(T obj, GenericObjectOutput out)` ????????????????????????
        cg.addMethod(cpf.toString());
        try {
            // ?????????
            Class<?> wc = cg.toClass();
            // set static field.
            // ????????????????????????????????????????????????
            if (builder != null) {
                wc.getField("builder").set(null, builder);
            }
            // ?????? Builder ??????
            return (Builder<?>) wc.newInstance();
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e.getMessage());
        } finally {
            cg.release();
        }
    }

    private static Builder<?> newObjectBuilder(final Class<?> c) {
        // ?????????????????? Enum Builder ??????
        if (c.isEnum()) {
            return newEnumBuilder(c);
        }

        // ??????????????????????????? RuntimeException ??????
        if (c.isAnonymousClass()) {
            throw new RuntimeException("Can not instantiation anonymous class: " + c);
        }

        // ??????????????????????????????????????? RuntimeException ??????
        if (c.getEnclosingClass() != null && !Modifier.isStatic(c.getModifiers())) {
            throw new RuntimeException("Can not instantiation inner and non-static class: " + c);
        }

        // Throwable ????????????????????? Serialize Builder ??????
        if (Throwable.class.isAssignableFrom(c)) {
            return SerializableBuilder;
        }

        ClassLoader cl = ClassHelper.getCallerClassLoader(Builder.class);

        // is same package.
        boolean isp; // ??????????????????
        String cn = c.getName(), // ????????????
                bcn; // ????????? Builder ??????
        if (c.getClassLoader() == null) { // ??????????????????????????? Builder ????????????
            // is system class. if( cn.startsWith("java.") || cn.startsWith("javax.") || cn.startsWith("sun.") )
            isp = false;
            bcn = BUILDER_CLASS_NAME + "$bc" + BUILDER_CLASS_COUNTER.getAndIncrement();
        } else { // ???????????????????????? c ????????????
            isp = true;
            bcn = cn + "$bc" + BUILDER_CLASS_COUNTER.getAndIncrement();
        }

        // is Collection, is Map, is Serializable.
        boolean isc = Collection.class.isAssignableFrom(c); // ?????? Collection
        boolean ism = !isc && Map.class.isAssignableFrom(c); // ?????? Map
        boolean iss = !(isc || ism) && Serializable.class.isAssignableFrom(c); // ?????? Serializable

        // ??????????????? `.fc` ?????????????????????????????????????????????
        // ??????????????? SimpleDO.java ??? SimpleDO.fc
        // deal with fields.
        String[] fns = null; // fix-order fields names
        InputStream is = c.getResourceAsStream(c.getSimpleName() + FIELD_CONFIG_SUFFIX); // load field-config file.
        if (is != null) {
            try {
                int len = is.available();
                if (len > 0) {
                    if (len > MAX_FIELD_CONFIG_FILE_SIZE) {
                        throw new RuntimeException("Load [" + c.getName() + "] field-config file error: File-size too larger");
                    }
                    // ????????????
                    String[] lines = IOUtils.readLines(is);
                    if (lines.length > 0) {
                        List<String> list = new ArrayList<String>();
                        // ??????
                        for (String line : lines) {
                            fns = line.split(",");
                            Arrays.sort(fns, FNC);
                            list.addAll(Arrays.asList(fns));
                        }
                        fns = list.toArray(new String[0]);
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("Load [" + c.getName() + "] field-config file error: " + e.getMessage());
            } finally {
                try {
                    is.close();
                } catch (IOException ignored) {
                }
            }
        }

        Field f, fs[];
        if (fns != null) {
            // ?????? `.fc` ????????????????????????????????????
            fs = new Field[fns.length];
            for (int i = 0; i < fns.length; i++) {
                String fn = fns[i];
                try {
                    // ?????????????????????
                    f = c.getDeclaredField(fn);
                    // ????????????????????????????????????????????????????????? RuntimeException ??????
                    int mod = f.getModifiers();
                    if (Modifier.isStatic(mod) || (serializeIgnoreFinalModifier(c) && Modifier.isFinal(mod))) {
                        throw new RuntimeException("Field [" + c.getName() + "." + fn + "] is static/final field.");
                    }
                    // ??? transient ?????????????????????????????? Serializable ?????????????????? Serialize Builder ??????
                    if (Modifier.isTransient(mod)) {
                        if (iss) {
                            return SerializableBuilder;
                        }
                        // ???????????? Serializable ??????????????? RuntimeException ??????
                        throw new RuntimeException("Field [" + c.getName() + "." + fn + "] is transient field.");
                    }
                    // ???????????????
                    f.setAccessible(true);
                    fs[i] = f;
                } catch (SecurityException e) {
                    throw new RuntimeException(e.getMessage());
                } catch (NoSuchFieldException e) {
                    throw new RuntimeException("Field [" + c.getName() + "." + fn + "] not found.");
                }
            }
        } else {
            // ????????????????????????????????????
            Class<?> t = c;
            List<Field> fl = new ArrayList<Field>();
            do {
                fs = t.getDeclaredFields();
                for (Field tf : fs) {
                    int mod = tf.getModifiers();
                    // ???????????????????????????????????????
                    if (Modifier.isStatic(mod) // ??????????????????
                            || (serializeIgnoreFinalModifier(c) && Modifier.isFinal(mod))
                            || tf.getName().equals("this$0") // skip static or inner-class's 'this$0' field.
                            || !Modifier.isPublic(tf.getType().getModifiers())) { //skip private inner-class field
                        continue;
                    }
                    // ??? transient ?????????????????????????????? Serializable ?????????????????? Serialize Builder ??????
                    if (Modifier.isTransient(mod)) {
                        if (iss) {
                            return SerializableBuilder;
                        }
                        continue;
                    }
                    // ???????????????
                    tf.setAccessible(true); // ???????????????
                    fl.add(tf);
                }
                // ????????????
                t = t.getSuperclass();
            } while (t != Object.class);

            // ??????
            fs = fl.toArray(new Field[0]);
            if (fs.length > 1) {
                Arrays.sort(fs, FC);
            }
        }

        // ????????????????????????
        // deal with constructors.
        Constructor<?>[] cs = c.getDeclaredConstructors();
        if (cs.length == 0) {
            Class<?> t = c;
            do {
                t = t.getSuperclass();
                if (t == null) { // ????????????????????????????????? RuntimeException ??????
                    throw new RuntimeException("Can not found Constructor?");
                }
                cs = t.getDeclaredConstructors();
            } while (cs.length == 0);
        }
        // ??????
        if (cs.length > 1) {
            Arrays.sort(cs, CC);
        }

        // `#writeObject(T obj, GenericObjectOutput out)` ??????????????????????????????????????????????????????????????? + ??????????????????????????????
        // writeObject code.
        StringBuilder cwf = new StringBuilder("protected void writeObject(Object obj, ").append(GenericObjectOutput.class.getName()).append(" out) throws java.io.IOException{");
        cwf.append(cn).append(" v = (").append(cn).append(")$1; ");
        cwf.append("$2.writeInt(fields.length);");

        // `#readObject(T ret, GenericObjectInput in)` ??????????????????????????????????????????????????????????????? + ??????????????????????????????
        // readObject code.
        StringBuilder crf = new StringBuilder("protected void readObject(Object ret, ").append(GenericObjectInput.class.getName()).append(" in) throws java.io.IOException{");
        crf.append("int fc = $2.readInt();");
        crf.append("if( fc != ").append(fs.length).append(" ) throw new IllegalStateException(\"Deserialize Class [").append(cn).append("], field count not matched. Expect ").append(fs.length).append(" but get \" + fc +\".\");");
        crf.append(cn).append(" ret = (").append(cn).append(")$1;");

        // `#newInstance(GenericObjectInput in)` ????????????????????????????????????
        // newInstance code.
        StringBuilder cni = new StringBuilder("protected Object newInstance(").append(GenericObjectInput.class.getName()).append(" in){ return ");
        Constructor<?> con = cs[0]; // `c` ????????????????????????
        int mod = con.getModifiers();
        boolean dn = Modifier.isPublic(mod) || (isp && !Modifier.isPrivate(mod)); // ?????????????????????
        if (dn) { // ????????????
            cni.append("new ").append(cn).append("(");
        } else { // ????????????
            con.setAccessible(true);
            cni.append("constructor.newInstance(new Object[]{");
        }
        // `c` ????????????????????????????????????????????????????????????????????????????????????
        Class<?>[] pts = con.getParameterTypes();
        for (int i = 0; i < pts.length; i++) {
            if (i > 0) {
                cni.append(',');
            }
            cni.append(defaultArg(pts[i]));
        }
        // ?????? `#newInstance(GenericObjectInput in)` ?????????
        if (!dn) {
            cni.append("}"); // close object array.
        }
        cni.append("); }");

        // ?????? PropertyMetadata ??????
        // get bean-style property metadata.
        Map<String, PropertyMetadata> pms = propertyMetadatas(c);
        // ??????????????? Builder ??????
        List<Builder<?>> builders = new ArrayList<Builder<?>>(fs.length);
        String fn, ftn; // field name, field type name.
        Class<?> ft; // field type.
        boolean da; // direct access.
        PropertyMetadata pm;
        for (int i = 0; i < fs.length; i++) {
            f = fs[i];
            fn = f.getName();
            ft = f.getType();
            ftn = ReflectUtils.getName(ft);
            da = isp && (f.getDeclaringClass() == c) && (!Modifier.isPrivate(f.getModifiers())); // direct access ?????????????????????????????????????????? setting / getting ??????
            if (da) {
                pm = null;
            } else {
                pm = pms.get(fn);
                if (pm != null && (pm.type != ft || pm.setter == null || pm.getter == null)) {
                    pm = null;
                }
            }

            // TODO ???TODO 8035???1?????????????????????????????????????????????????????????
            crf.append("if( fc == ").append(i).append(" ) return;");
            // ????????????????????? GenericDataOutput ??? GenericDataInput ??????????????????????????????????????????
            if (ft.isPrimitive()) {
                // ????????????????????? `#writeObject(T obj, GenericObjectOutput out)` ??? `#readObject(T ret, GenericObjectInput in)` ???????????????????????????????????????
                if (ft == boolean.class) {
                    if (da) { // ????????????
                        cwf.append("$2.writeBool(v.").append(fn).append(");");
                        crf.append("ret.").append(fn).append(" = $2.readBool();");
                    } else if (pm != null) { // setting/getting ????????????
                        cwf.append("$2.writeBool(v.").append(pm.getter).append("());");
                        crf.append("ret.").append(pm.setter).append("($2.readBool());");
                    } else { // ????????????
                        cwf.append("$2.writeBool(((Boolean)fields[").append(i).append("].get($1)).booleanValue());");
                        crf.append("fields[").append(i).append("].set(ret, ($w)$2.readBool());");
                    }
                } else if (ft == byte.class) {
                    if (da) {
                        cwf.append("$2.writeByte(v.").append(fn).append(");");
                        crf.append("ret.").append(fn).append(" = $2.readByte();");
                    } else if (pm != null) {
                        cwf.append("$2.writeByte(v.").append(pm.getter).append("());");
                        crf.append("ret.").append(pm.setter).append("($2.readByte());");
                    } else {
                        cwf.append("$2.writeByte(((Byte)fields[").append(i).append("].get($1)).byteValue());");
                        crf.append("fields[").append(i).append("].set(ret, ($w)$2.readByte());");
                    }
                } else if (ft == char.class) {
                    if (da) {
                        cwf.append("$2.writeShort((short)v.").append(fn).append(");");
                        crf.append("ret.").append(fn).append(" = (char)$2.readShort();");
                    } else if (pm != null) {
                        cwf.append("$2.writeShort((short)v.").append(pm.getter).append("());");
                        crf.append("ret.").append(pm.setter).append("((char)$2.readShort());");
                    } else {
                        cwf.append("$2.writeShort((short)((Character)fields[").append(i).append("].get($1)).charValue());");
                        crf.append("fields[").append(i).append("].set(ret, ($w)((char)$2.readShort()));");
                    }
                } else if (ft == short.class) {
                    if (da) {
                        cwf.append("$2.writeShort(v.").append(fn).append(");");
                        crf.append("ret.").append(fn).append(" = $2.readShort();");
                    } else if (pm != null) {
                        cwf.append("$2.writeShort(v.").append(pm.getter).append("());");
                        crf.append("ret.").append(pm.setter).append("($2.readShort());");
                    } else {
                        cwf.append("$2.writeShort(((Short)fields[").append(i).append("].get($1)).shortValue());");
                        crf.append("fields[").append(i).append("].set(ret, ($w)$2.readShort());");
                    }
                } else if (ft == int.class) {
                    if (da) {
                        cwf.append("$2.writeInt(v.").append(fn).append(");");
                        crf.append("ret.").append(fn).append(" = $2.readInt();");
                    } else if (pm != null) {
                        cwf.append("$2.writeInt(v.").append(pm.getter).append("());");
                        crf.append("ret.").append(pm.setter).append("($2.readInt());");
                    } else {
                        cwf.append("$2.writeInt(((Integer)fields[").append(i).append("].get($1)).intValue());");
                        crf.append("fields[").append(i).append("].set(ret, ($w)$2.readInt());");
                    }
                } else if (ft == long.class) {
                    if (da) {
                        cwf.append("$2.writeLong(v.").append(fn).append(");");
                        crf.append("ret.").append(fn).append(" = $2.readLong();");
                    } else if (pm != null) {
                        cwf.append("$2.writeLong(v.").append(pm.getter).append("());");
                        crf.append("ret.").append(pm.setter).append("($2.readLong());");
                    } else {
                        cwf.append("$2.writeLong(((Long)fields[").append(i).append("].get($1)).longValue());");
                        crf.append("fields[").append(i).append("].set(ret, ($w)$2.readLong());");
                    }
                } else if (ft == float.class) {
                    if (da) {
                        cwf.append("$2.writeFloat(v.").append(fn).append(");");
                        crf.append("ret.").append(fn).append(" = $2.readFloat();");
                    } else if (pm != null) {
                        cwf.append("$2.writeFloat(v.").append(pm.getter).append("());");
                        crf.append("ret.").append(pm.setter).append("($2.readFloat());");
                    } else {
                        cwf.append("$2.writeFloat(((Float)fields[").append(i).append("].get($1)).floatValue());");
                        crf.append("fields[").append(i).append("].set(ret, ($w)$2.readFloat());");
                    }
                } else if (ft == double.class) {
                    if (da) {
                        cwf.append("$2.writeDouble(v.").append(fn).append(");");
                        crf.append("ret.").append(fn).append(" = $2.readDouble();");
                    } else if (pm != null) {
                        cwf.append("$2.writeDouble(v.").append(pm.getter).append("());");
                        crf.append("ret.").append(pm.setter).append("($2.readDouble());");
                    } else {
                        cwf.append("$2.writeDouble(((Double)fields[").append(i).append("].get($1)).doubleValue());");
                        crf.append("fields[").append(i).append("].set(ret, ($w)$2.readDouble());");
                    }
                }
                // ?????????????????? `c` ?????????????????? this ?????? `c` ????????? Builder ?????????
            } else if (ft == c) {
                // ????????????????????? `#writeObject(T obj, GenericObjectOutput out)` ??? `#readObject(T ret, GenericObjectInput in)` ???????????????????????????????????????
                if (da) {
                    cwf.append("this.writeTo(v.").append(fn).append(", $2);");
                    crf.append("ret.").append(fn).append(" = (").append(ftn).append(")this.parseFrom($2);");
                } else if (pm != null) {
                    cwf.append("this.writeTo(v.").append(pm.getter).append("(), $2);");
                    crf.append("ret.").append(pm.setter).append("((").append(ftn).append(")this.parseFrom($2));");
                } else {
                    cwf.append("this.writeTo((").append(ftn).append(")fields[").append(i).append("].get($1), $2);");
                    crf.append("fields[").append(i).append("].set(ret, this.parseFrom($2));");
                }
                // ?????????????????????????????? Builder ??????
            } else {
                // ????????????????????? Builder ??????
                int bc = builders.size();
                builders.add(register(ft)); // ????????????????????????????????????????????????????????? Builder ??????
                // ????????????????????? `#writeObject(T obj, GenericObjectOutput out)` ??? `#readObject(T ret, GenericObjectInput in)` ???????????????????????????????????????
                if (da) {
                    cwf.append("builders[").append(bc).append("].writeTo(v.").append(fn).append(", $2);");
                    crf.append("ret.").append(fn).append(" = (").append(ftn).append(")builders[").append(bc).append("].parseFrom($2);");
                } else if (pm != null) {
                    cwf.append("builders[").append(bc).append("].writeTo(v.").append(pm.getter).append("(), $2);");
                    crf.append("ret.").append(pm.setter).append("((").append(ftn).append(")builders[").append(bc).append("].parseFrom($2));");
                } else {
                    cwf.append("builders[").append(bc).append("].writeTo((").append(ftn).append(")fields[").append(i).append("].get($1), $2);");
                    crf.append("fields[").append(i).append("].set(ret, builders[").append(bc).append("].parseFrom($2));");
                }
            }
        }

        // TODO ???TODO 8035???1?????????????????????????????????????????????????????????
        // skip any fields.
        crf.append("for(int i=").append(fs.length).append(";i<fc;i++) $2.skipAny();");

        // collection or map
        // Collection ????????????????????????????????? `#writeObject(T obj, GenericObjectOutput out)` ??? `#readObject(T ret, GenericObjectInput in)` ???????????????????????????????????????
        if (isc) {
            cwf.append("$2.writeInt(v.size()); for(java.util.Iterator it=v.iterator();it.hasNext();){ $2.writeObject(it.next()); }");
            crf.append("int len = $2.readInt(); for(int i=0;i<len;i++) ret.add($2.readObject());");
            // Map ??????????????? KV ???????????? `#writeObject(T obj, GenericObjectOutput out)` ??? `#readObject(T ret, GenericObjectInput in)` ???????????????????????????????????????
        } else if (ism) {
            cwf.append("$2.writeInt(v.size()); for(java.util.Iterator it=v.entrySet().iterator();it.hasNext();){ java.util.Map.Entry entry = (java.util.Map.Entry)it.next(); $2.writeObject(entry.getKey()); $2.writeObject(entry.getValue()); }");
            crf.append("int len = $2.readInt(); for(int i=0;i<len;i++) ret.put($2.readObject(), $2.readObject());");
        }
        cwf.append(" }");
        crf.append(" }");

        // ?????? Builder ???????????????
        ClassGenerator cg = ClassGenerator.newInstance(cl);
        // ????????????
        cg.setClassName(bcn);
        // ??????????????? AbstractObjectBuilder.class
        cg.setSuperClass(AbstractObjectBuilder.class);
        // ????????????????????????
        cg.addDefaultConstructor();
        // ?????? `fields` ????????????
        cg.addField("public static java.lang.reflect.Field[] fields;");
        // ?????? `builders` ????????????
        cg.addField("public static " + BUILDER_CLASS_NAME + "[] builders;");
        if (!dn) {
            cg.addField("public static java.lang.reflect.Constructor constructor;");
        }
        // ?????? `#getType()` ????????????????????????
        cg.addMethod("public Class getType(){ return " + cn + ".class; }");
        // ?????? `#writeObject(T obj, GenericObjectOutput out)` ????????????????????????
        cg.addMethod(cwf.toString());
        // ?????? `#readObject(T obj, GenericObjectOutput out)` ????????????????????????
        cg.addMethod(crf.toString());
        // ?????? `#newInstance(GenericObjectInput in)` ????????????????????????
        cg.addMethod(cni.toString());
        try {
            // ?????????
            Class<?> wc = cg.toClass();
            // ?????? `fields` `builders` ???????????????
            // set static field
            wc.getField("fields").set(null, fs);
            wc.getField("builders").set(null, builders.toArray(new Builder<?>[0]));
            // ????????????????????????????????????????????????
            if (!dn) {
                wc.getField("constructor").set(null, con);
            }
            // ?????? Builder ??????
            return (Builder<?>) wc.newInstance();
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            // ?????? ClassGenerator ??????
            cg.release();
        }
    }

    private static Builder<?> newEnumBuilder(Class<?> c) {
        ClassLoader cl = ClassHelper.getCallerClassLoader(Builder.class);
        // ????????????
        String cn = c.getName();
        // ????????? Builder ??????
        String bcn = BUILDER_CLASS_NAME + "$bc" + BUILDER_CLASS_COUNTER.getAndIncrement();

        // `#writeObject(T obj, GenericObjectOutput out)` ??????????????????????????????????????????????????????????????? + ?????????????????????????????????
        StringBuilder cwt = new StringBuilder("public void writeTo(Object obj, ").append(GenericObjectOutput.class.getName()).append(" out) throws java.io.IOException{"); // writeTo code.
        cwt.append(cn).append(" v = (").append(cn).append(")$1;");
        cwt.append("if( $1 == null ){ $2.writeUTF(null); }else{ $2.writeUTF(v.name()); } }");

        // `#readObject(T ret, GenericObjectInput in)` ??????????????????????????????????????????????????????????????? + Enum.valueOf(Class, String) ???
        StringBuilder cpf = new StringBuilder("public Object parseFrom(").append(GenericObjectInput.class.getName()).append(" in) throws java.io.IOException{"); // parseFrom code.
        cpf.append("String name = $1.readUTF(); if( name == null ) return null; return (").append(cn).append(")Enum.valueOf(").append(cn).append(".class, name); }");

        // ?????? Builder ???????????????
        ClassGenerator cg = ClassGenerator.newInstance(cl);
        // ????????????
        cg.setClassName(bcn);
        // ??????????????? Builder.class
        cg.setSuperClass(Builder.class);
        // ????????????????????????
        cg.addDefaultConstructor();
        cg.addMethod("public Class getType(){ return " + cn + ".class; }");
        cg.addMethod(cwt.toString());
        cg.addMethod(cpf.toString());
        try {
            Class<?> wc = cg.toClass();
            return (Builder<?>) wc.newInstance();
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            cg.release();
        }
    }

    private static Map<String, PropertyMetadata> propertyMetadatas(Class<?> c) {
        // ?????? public ????????????
        Map<String, Method> mm = new HashMap<String, Method>(); // method map.
        // All public method.
        for (Method m : c.getMethods()) {
            if (m.getDeclaringClass() == Object.class) // Ignore Object's method.
                continue;
            mm.put(ReflectUtils.getDesc(m), m);
        }

        // ?????? PropertyMetadata ?????????KEY ???????????????
        Map<String, PropertyMetadata> ret = new HashMap<String, PropertyMetadata>(); // property metadata map.
        Matcher matcher;
        for (Map.Entry<String, Method> entry : mm.entrySet()) {
            String desc = entry.getKey();
            Method method = entry.getValue();
            // setting ??????
            if ((matcher = ReflectUtils.GETTER_METHOD_DESC_PATTERN.matcher(desc)).matches() ||
                    (matcher = ReflectUtils.IS_HAS_CAN_METHOD_DESC_PATTERN.matcher(desc)).matches()) {
                String pn = propertyName(matcher.group(1));
                Class<?> pt = method.getReturnType();
                PropertyMetadata pm = ret.get(pn);
                if (pm == null) { // ????????????????????? PropertyMetadata ??????
                    pm = new PropertyMetadata();
                    pm.type = pt;
                    ret.put(pn, pm);
                } else {
                    if (pm.type != pt) {
                        continue;
                    }
                }
                pm.getter = method.getName();
                // setting ??????
            } else if ((matcher = ReflectUtils.SETTER_METHOD_DESC_PATTERN.matcher(desc)).matches()) {
                String pn = propertyName(matcher.group(1));
                Class<?> pt = method.getParameterTypes()[0];
                PropertyMetadata pm = ret.get(pn);
                if (pm == null) { // ????????????????????? PropertyMetadata ??????
                    pm = new PropertyMetadata();
                    pm.type = pt;
                    ret.put(pn, pm);
                } else {
                    if (pm.type != pt) {
                        continue;
                    }
                }
                pm.setter = method.getName();
            }
        }
        return ret;
    }

    private static String propertyName(String s) {
        return s.length() == 1 || Character.isLowerCase(s.charAt(1)) ? Character.toLowerCase(s.charAt(0)) + s.substring(1) : s;
    }

    private static boolean serializeIgnoreFinalModifier(Class cl) {
//	    if (cl.isAssignableFrom(BigInteger.class)) return false;
//	    for performance
//	    if (cl.getName().startsWith("java")) return true;
//	    if (cl.getName().startsWith("javax")) return true;

        return false;
    }

    @SuppressWarnings("unused")
    private static boolean isPrimitiveOrPrimitiveArray1(Class<?> cl) {
        if (cl.isPrimitive()) {
            return true;
        } else {
            Class clazz = cl.getClass().getComponentType();
            return clazz != null && clazz.isPrimitive();
        }
    }

    private static String defaultArg(Class<?> cl) {
        if (boolean.class == cl) return "false";
        if (int.class == cl) return "0";
        if (long.class == cl) return "0l";
        if (double.class == cl) return "(double)0";
        if (float.class == cl) return "(float)0";
        if (short.class == cl) return "(short)0";
        if (char.class == cl) return "(char)0";
        if (byte.class == cl) return "(byte)0";
        if (byte[].class == cl) return "new byte[]{0}";
        if (!cl.isPrimitive()) return "null";
        throw new UnsupportedOperationException();
    }

    private static int compareFieldName(String n1, String n2) {
        int l = Math.min(n1.length(), n2.length());
        for (int i = 0; i < l; i++) {
            int t = n1.charAt(i) - n2.charAt(i);
            if (t != 0) {
                return t;
            }
        }
        return n1.length() - n2.length();
    }

    /**
     * ???????????????????????????
     *
     * @param c ???
     */
    private static void addDesc(Class<?> c) {
        String desc = ReflectUtils.getDesc(c); // ?????????java.lang.Byte ??? Ljava/lang/Byte;
        // ??????????????????
        int index = mDescList.size();
        mDescList.add(desc);
        mDescMap.put(desc, index);
    }

    // ========== ???????????? BEGIN  ==========

    /**
     * @return Builder ????????????
     */
    abstract public Class<T> getType();

    /**
     * ?????????????????? GenericObjectOutput ??????????????????
     *
     * @param obj ??????
     * @param out GenericObjectOutput ??????
     * @throws IOException ????????? IO ????????????
     */
    abstract public void writeTo(T obj, GenericObjectOutput out) throws IOException;

    // ????????? ??????????????????
    public void writeTo(T obj, OutputStream os) throws IOException {
        // ??? OutputStream ????????? GenericObjectOutput ??????
        GenericObjectOutput out = new GenericObjectOutput(os);
        // ??????
        writeTo(obj, out);
        // ??????
        out.flushBuffer();
    }

    /**
     * ???????????? GenericObjectInput ?????????
     *
     * @param in GenericObjectInput ??????
     * @return ??????
     * @throws IOException ??? IO ???????????????
     */
    abstract public T parseFrom(GenericObjectInput in) throws IOException;

    // ????????? ??????????????????
    public T parseFrom(InputStream is) throws IOException {
        return parseFrom(new GenericObjectInput(is)); // ??? InputStream ????????? GenericObjectInput ??????
    }

    // ????????? ??????????????????
    public T parseFrom(byte[] b) throws IOException {
        return parseFrom(new UnsafeByteArrayInputStream(b)); // ??? byte[] ????????? InputStream ??????
    }

    // ========== ???????????? END  ==========

    /**
     * ???????????????
     */
    static class PropertyMetadata {

        /**
         * ??????
         */
        Class<?> type;

        /**
         * ???????????????????????????
         */
        String setter;

        /**
         * ???????????????????????????
         */
        String getter;

    }

    /**
     * Builder ?????????
     *
     * @param <T> ??????
     */
    public static abstract class AbstractObjectBuilder<T> extends Builder<T> {

        @Override
        public void writeTo(T obj, GenericObjectOutput out) throws IOException {
            // NULL ????????? OBJECT_NULL ??? mBuffer ???
            if (obj == null) {
                out.write0(OBJECT_NULL);
            } else {
                // ??????????????????????????????
                int ref = out.getRef(obj);
                if (ref < 0) { // ?????????
                    // ????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
                    out.addRef(obj);
                    // ?????? OBJECT ??? mBuffer ???
                    out.write0(OBJECT);
                    // ?????? ?????? ??? mBuffer ??????
                    writeObject(obj, out);
                } else { // ??????
                    // ?????? OBJECT_REF ??? mBuffer ???
                    out.write0(OBJECT_REF);
                    // ?????? ???????????????????????? ??? mBuffer ???
                    out.writeUInt(ref);
                }
            }
        }

        @Override
        public T parseFrom(GenericObjectInput in) throws IOException {
            // ??????????????????
            byte b = in.read0();
            switch (b) {
                // ??????
                case OBJECT: {
                    // ????????????
                    T ret = newInstance(in);
                    // ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
                    in.addRef(ret);
                    // ???????????? GenericObjectInput ?????????
                    readObject(ret, in);
                    // ??????
                    return ret;
                }
                // ????????????????????????
                case OBJECT_REF:
                    // ??????????????????????????????
                    // ?????????????????????
                    return (T) in.getRef(in.readUInt());
                // NULL ????????? null
                case OBJECT_NULL:
                    return null;
                default:
                    throw new IOException("Input format error, expect OBJECT|OBJECT_REF|OBJECT_NULL, get " + b);
            }
        }

        /**
         * ?????? Builder ??????????????????
         *
         * @param in GenericObjectInput ??????
         * @return ??????????????????
         * @throws IOException ??? IO ???????????????
         */
        abstract protected T newInstance(GenericObjectInput in) throws IOException;

        /**
         * ?????????????????? GenericObjectOutput ??????????????????
         *
         * @param obj ??????
         * @param out GenericObjectOutput ??????
         * @throws IOException ??? IO ???????????????
         */
        abstract protected void writeObject(T obj, GenericObjectOutput out) throws IOException;

        /**
         * ???????????? GenericObjectInput ?????????
         *
         * @param ret ?????????
         *            ???????????? {@link #parseFrom(GenericObjectInput)} ???????????? {@link #newInstance(GenericObjectInput)} ??????
         * @param in  GenericObjectInput ??????
         * @throws IOException ??? IO ???????????????
         */
        abstract protected void readObject(T ret, GenericObjectInput in) throws IOException;

    }

}