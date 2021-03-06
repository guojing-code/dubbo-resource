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
package com.alibaba.dubbo.validation.support.jvalidation;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.bytecode.ClassGenerator;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.validation.MethodValidated;
import com.alibaba.dubbo.validation.Validator;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtNewConstructor;
import javassist.Modifier;
import javassist.NotFoundException;
import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.ClassFile;
import javassist.bytecode.ConstPool;
import javassist.bytecode.annotation.ArrayMemberValue;
import javassist.bytecode.annotation.BooleanMemberValue;
import javassist.bytecode.annotation.ByteMemberValue;
import javassist.bytecode.annotation.CharMemberValue;
import javassist.bytecode.annotation.ClassMemberValue;
import javassist.bytecode.annotation.DoubleMemberValue;
import javassist.bytecode.annotation.EnumMemberValue;
import javassist.bytecode.annotation.FloatMemberValue;
import javassist.bytecode.annotation.IntegerMemberValue;
import javassist.bytecode.annotation.LongMemberValue;
import javassist.bytecode.annotation.MemberValue;
import javassist.bytecode.annotation.ShortMemberValue;
import javassist.bytecode.annotation.StringMemberValue;

import javax.validation.Constraint;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import javax.validation.Validation;
import javax.validation.ValidatorFactory;
import javax.validation.groups.Default;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JValidator
 * <p>
 * ?????? [JSR303](https://jcp.org/en/jsr/detail?id=303) ?????????????????????????????? JSR303 ??????????????? Annotation
 */
public class JValidator implements Validator {

    private static final Logger logger = LoggerFactory.getLogger(JValidator.class);

    /**
     * ???????????????
     */
    private final Class<?> clazz;

    /**
     * Validator ??????
     */
    private final javax.validation.Validator validator;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public JValidator(URL url) {
        // ?????????????????????
        this.clazz = ReflectUtils.forName(url.getServiceInterface());
        // ?????? `"jvalidation"` ?????????
        String jvalidation = url.getParameter("jvalidation");
        // ?????? ValidatorFactory ??????
        ValidatorFactory factory;
        if (jvalidation != null && jvalidation.length() > 0) { // ????????????
            factory = Validation.byProvider((Class) ReflectUtils.forName(jvalidation)).configure().buildValidatorFactory();
        } else { // ??????
            factory = Validation.buildDefaultValidatorFactory();
        }
        // ?????? javax Validator ??????
        this.validator = factory.getValidator();
    }


    private static boolean isPrimitives(Class<?> cls) {
        // [] ????????????????????????????????????
        if (cls.isArray()) {
            return isPrimitive(cls.getComponentType());
        }
        // ????????????
        return isPrimitive(cls);
    }

    private static boolean isPrimitive(Class<?> cls) {
        return cls.isPrimitive() || cls == String.class || cls == Boolean.class || cls == Character.class
                || Number.class.isAssignableFrom(cls) || Date.class.isAssignableFrom(cls);
    }

    /**
     * ??????????????????????????? Bean ?????????
     * <p>
     * ????????? Bean ?????????????????????????????????????????? Javassist ?????????????????????
     *
     * @param clazz  ???????????????
     * @param method ??????
     * @param args   ????????????
     * @return Bean ??????
     */
    private static Object getMethodParameterBean(Class<?> clazz, Method method, Object[] args) {
        // ??? Constraint ???????????????????????????????????? Bean ?????????
        if (!hasConstraintParameter(method)) {
            return null;
        }
        try {
            // ?????? Bean ??????
            String parameterClassName = generateMethodParameterClassName(clazz, method);
            Class<?> parameterClass;
            try {
                // ?????? Bean ???
                parameterClass = Class.forName(parameterClassName, true, clazz.getClassLoader());
            } catch (ClassNotFoundException e) { // ????????????????????? Javassist ??????????????????
                // ?????? ClassPool ??????
                ClassPool pool = ClassGenerator.getClassPool(clazz.getClassLoader());
                // ?????? CtClass ??????
                CtClass ctClass = pool.makeClass(parameterClassName);
                // ?????? Java ????????? 5
                ClassFile classFile = ctClass.getClassFile();
                classFile.setVersionToJava5();
                // ????????????????????????
                ctClass.addConstructor(CtNewConstructor.defaultConstructor(pool.getCtClass(parameterClassName)));
                // ??????????????????????????????????????????????????????
                // parameter fields
                Class<?>[] parameterTypes = method.getParameterTypes();
                Annotation[][] parameterAnnotations = method.getParameterAnnotations();
                for (int i = 0; i < parameterTypes.length; i++) {
                    Class<?> type = parameterTypes[i];
                    Annotation[] annotations = parameterAnnotations[i];
                    // ??????????????????
                    AnnotationsAttribute attribute = new AnnotationsAttribute(classFile.getConstPool(), AnnotationsAttribute.visibleTag);
                    // ???????????????????????????????????????
                    for (Annotation annotation : annotations) {
                        if (annotation.annotationType().isAnnotationPresent(Constraint.class)) { // ?????????????????????????????? @NotNull
                            javassist.bytecode.annotation.Annotation ja = new javassist.bytecode.annotation.Annotation(
                                    classFile.getConstPool(), pool.getCtClass(annotation.annotationType().getName()));
                            // ???????????????????????????
                            Method[] members = annotation.annotationType().getMethods();
                            for (Method member : members) {
                                if (Modifier.isPublic(member.getModifiers())
                                        && member.getParameterTypes().length == 0
                                        && member.getDeclaringClass() == annotation.annotationType()) {
                                    // ????????????????????????????????????
                                    Object value = member.invoke(annotation);
                                    if (null != value) {
                                        MemberValue memberValue = createMemberValue(
                                                classFile.getConstPool(), pool.get(member.getReturnType().getName()), value);
                                        ja.addMemberValue(member.getName(), memberValue);
                                    }
                                }
                            }
                            attribute.addAnnotation(ja);
                        }
                    }
                    // ????????????
                    String fieldName = method.getName() + "Argument" + i;
                    CtField ctField = CtField.make("public " + type.getCanonicalName() + " " + fieldName + ";", pool.getCtClass(parameterClassName));
                    ctField.getFieldInfo().addAttribute(attribute);
                    // ????????????
                    ctClass.addField(ctField);
                }
                // ?????????
                parameterClass = ctClass.toClass(clazz.getClassLoader(), null);
            }
            // ?????? Bean ??????
            Object parameterBean = parameterClass.newInstance();
            // ?????? Bean ???????????????????????????
            for (int i = 0; i < args.length; i++) {
                Field field = parameterClass.getField(method.getName() + "Argument" + i);
                field.set(parameterBean, args[i]);
            }
            return parameterBean;
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
            return null;
        }
    }


    //                String file = "/Users/yunai/test/" + ctClass.getName() + ".class";
//                for (int i = 0; i < 4; i++) {
//                    file = file.replaceFirst("\\.", "/");
//                }
//                System.out.println(file);
//                ctClass.debugWriteFile( "/Users/yunai/test/" + parameterClass.getName() + ".class");

    private static String generateMethodParameterClassName(Class<?> clazz, Method method) {
        StringBuilder builder = new StringBuilder().append(clazz.getName())
                .append("_")
                .append(toUpperMethodName(method.getName()))
                .append("Parameter");

        Class<?>[] parameterTypes = method.getParameterTypes();
        for (Class<?> parameterType : parameterTypes) {
            builder.append("_").append(parameterType.getName());
        }

        return builder.toString();
    }

    private static boolean hasConstraintParameter(Method method) {
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        // ?????????????????????????????????
        if (parameterAnnotations != null && parameterAnnotations.length > 0) {
            // ???????????????????????????????????????
            for (Annotation[] annotations : parameterAnnotations) {
                // ????????? Constraint ??????
                for (Annotation annotation : annotations) {
                    if (annotation.annotationType().isAnnotationPresent(Constraint.class)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String toUpperMethodName(String methodName) {
        return methodName.substring(0, 1).toUpperCase() + methodName.substring(1);
    }

    // Copy from javassist.bytecode.annotation.Annotation.createMemberValue(ConstPool, CtClass);
    private static MemberValue createMemberValue(ConstPool cp, CtClass type, Object value) throws NotFoundException {
        MemberValue memberValue = javassist.bytecode.annotation.Annotation.createMemberValue(cp, type);
        if (memberValue instanceof BooleanMemberValue) // Boolean
            ((BooleanMemberValue) memberValue).setValue((Boolean) value);
        else if (memberValue instanceof ByteMemberValue) // Byte
            ((ByteMemberValue) memberValue).setValue((Byte) value);
        else if (memberValue instanceof CharMemberValue) // Char
            ((CharMemberValue) memberValue).setValue((Character) value);
        else if (memberValue instanceof ShortMemberValue) // Short
            ((ShortMemberValue) memberValue).setValue((Short) value);
        else if (memberValue instanceof IntegerMemberValue) // Integer
            ((IntegerMemberValue) memberValue).setValue((Integer) value);
        else if (memberValue instanceof LongMemberValue) // Long
            ((LongMemberValue) memberValue).setValue((Long) value);
        else if (memberValue instanceof FloatMemberValue) // Float
            ((FloatMemberValue) memberValue).setValue((Float) value);
        else if (memberValue instanceof DoubleMemberValue)
            ((DoubleMemberValue) memberValue).setValue((Double) value);
        else if (memberValue instanceof ClassMemberValue) // Class
            ((ClassMemberValue) memberValue).setValue(((Class<?>) value).getName());
        else if (memberValue instanceof StringMemberValue) // String
            ((StringMemberValue) memberValue).setValue((String) value);
        else if (memberValue instanceof EnumMemberValue) // Enum
            ((EnumMemberValue) memberValue).setValue(((Enum<?>) value).name());
            /* else if (memberValue instanceof AnnotationMemberValue) */
        else if (memberValue instanceof ArrayMemberValue) { // ??????
            CtClass arrayType = type.getComponentType();
            int len = Array.getLength(value);
            // ???????????????
            MemberValue[] members = new MemberValue[len];
            for (int i = 0; i < len; i++) {
                members[i] = createMemberValue(cp, arrayType, Array.get(value, i));
            }
            ((ArrayMemberValue) memberValue).setValue(members);
        }
        return memberValue;
    }

    @Override
    public void validate(String methodName, Class<?>[] parameterTypes, Object[] arguments) throws Exception {
        // ??????????????????
        List<Class<?>> groups = new ArrayList<Class<?>>();
        // ????????????????????????????????????????????????????????????????????????????????? `ValidationService#save(...)` ??????????????? `ValidationService.Save` ?????????
        String methodClassName = clazz.getName() + "$" + toUpperMethodName(methodName);
        Class<?> methodClass;
        try {
            methodClass = Class.forName(methodClassName, false, Thread.currentThread().getContextClassLoader());
            groups.add(methodClass);
        } catch (ClassNotFoundException e) {
        }
        // ?????????????????????????????? @MethodValidated ????????????????????????????????????????????????
        Method method = clazz.getMethod(methodName, parameterTypes);
        Class<?>[] methodClasses;
        if (method.isAnnotationPresent(MethodValidated.class)) {
            methodClasses = method.getAnnotation(MethodValidated.class).value();
            groups.addAll(Arrays.asList(methodClasses));
        }
        // ????????????????????? Default.class ?????????????????????????????? JSR 303 ????????????????????????????????????????????? Default.class ???
        // add into default group
        groups.add(0, Default.class);
        // ????????????????????????????????????????????????????????????
        groups.add(1, clazz);
        // convert list to array
        Class<?>[] classGroups = groups.toArray(new Class[0]);

        // ??????????????????
        Set<ConstraintViolation<?>> violations = new HashSet<ConstraintViolation<?>>();
        // ???????????????????????????????????? Bean ??????????????????JSR 303 ??? Java Bean Validation ?????? Bean ????????????
        Object parameterBean = getMethodParameterBean(clazz, method, arguments);
        // ????????????????????? Bean ?????????
        if (parameterBean != null) {
            violations.addAll(validator.validate(parameterBean, classGroups));
        }
        // ?????????????????????????????????
        for (Object arg : arguments) {
            validate(violations, arg, classGroups);
        }
        // ????????????????????? ConstraintViolationException ?????????
        if (!violations.isEmpty()) {
            logger.error("Failed to validate service: " + clazz.getName() + ", method: " + methodName + ", cause: " + violations);
            throw new ConstraintViolationException("Failed to validate service: " + clazz.getName() + ", method: " + methodName + ", cause: " + violations, violations);
        }
    }

    /**
     * ??????????????????
     *
     * @param violations ??????????????????
     * @param arg        ??????
     * @param groups     ??????????????????
     */
    private void validate(Set<ConstraintViolation<?>> violations, Object arg, Class<?>... groups) {
        if (arg != null && !isPrimitives(arg.getClass())) {
            // [] ??????
            if (Object[].class.isInstance(arg)) {
                for (Object item : (Object[]) arg) {
                    validate(violations, item, groups); // ????????????
                }
                // Collection
            } else if (Collection.class.isInstance(arg)) {
                for (Object item : (Collection<?>) arg) {
                    validate(violations, item, groups); // ????????????
                }
                // Map
            } else if (Map.class.isInstance(arg)) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) arg).entrySet()) {
                    validate(violations, entry.getKey(), groups); // ????????????
                    validate(violations, entry.getValue(), groups); // ????????????
                }
                // ????????????
            } else {
                violations.addAll(validator.validate(arg, groups));
            }
        }
    }

}
