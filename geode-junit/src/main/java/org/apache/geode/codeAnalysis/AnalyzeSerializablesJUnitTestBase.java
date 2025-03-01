/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.codeAnalysis;

import static java.util.Collections.emptyList;
import static org.apache.geode.distributed.ConfigurationProperties.SERIALIZABLE_OBJECT_FILTER;
import static org.apache.geode.distributed.ConfigurationProperties.VALIDATE_SERIALIZABLE_OBJECTS;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.Externalizable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.geode.CancelException;
import org.apache.geode.DataSerializable;
import org.apache.geode.DataSerializer;
import org.apache.geode.codeAnalysis.decode.CompiledClass;
import org.apache.geode.codeAnalysis.decode.CompiledField;
import org.apache.geode.distributed.internal.DistributionConfigImpl;
import org.apache.geode.internal.InternalDataSerializer;
import org.apache.geode.internal.serialization.BufferDataOutputStream;
import org.apache.geode.internal.serialization.DataSerializableFixedID;
import org.apache.geode.internal.serialization.KnownVersion;
import org.apache.geode.pdx.internal.TypeRegistry;
import org.apache.geode.test.junit.categories.SerializationTest;
import org.apache.geode.unsafe.internal.sun.reflect.ReflectionFactory;

/**
 * This subclass of AbstractAnalyzeSerializablesTestBase uses DataSerializer and
 * InternalDataSerializer. It also performs initialization of the Geode TypeRegistry
 */
@Category(SerializationTest.class)
public abstract class AnalyzeSerializablesJUnitTestBase
    extends AnalyzeDataSerializablesJUnitTestBase {

  private static final String ACTUAL_SERIALIZABLES_DAT = "actualSerializables.dat";

  private final String expectedSerializablesFileName =
      "sanctioned-" + getModuleName() + "-serializables.txt";

  protected List<ClassAndVariableDetails> expectedSerializables;

  @Before
  public void setUp() throws Exception {
    TypeRegistry.init();
  }

  @Test
  public void testSerializables() throws Exception {
    System.out.println(testName.getMethodName() + " starting");
    findClasses();
    loadExpectedSerializables();

    File actualSerializablesFile = createEmptyFile(ACTUAL_SERIALIZABLES_DAT);
    System.out.println(testName.getMethodName() + " actualSerializablesFile="
        + actualSerializablesFile.getAbsolutePath());

    List<ClassAndVariables> actualSerializables = findSerializables();
    CompiledClassUtils.storeClassesAndVariables(actualSerializables, actualSerializablesFile);

    String diff = CompiledClassUtils
        .diffSortedClassesAndVariables(expectedSerializables, actualSerializables);
    if (!diff.isEmpty()) {
      System.out.println(
          "++++++++++++++++++++++++++++++testSerializables found discrepancies++++++++++++++++++++++++++++++++++++");
      System.out.println(diff);
      fail(diff + FAIL_MESSAGE, toBuildPathString(getResourceAsFile(EXCLUDED_CLASSES_TXT)),
          actualSerializablesFile.getAbsolutePath(),
          mainResourceToSourcePath(
              getResource(getModuleClass(), expectedSerializablesFileName).toURI()));
    }
  }

  @Test
  public void testSanctionedClassesExistAndDoDeserialize() throws Exception {
    loadExpectedSerializables();
    Set<String> openBugs = new HashSet<>(loadOpenBugs(getResourceAsFile(OPEN_BUGS_TXT)));

    initializeSerializationService();

    for (ClassAndVariableDetails details : expectedSerializables) {
      if (openBugs.contains(details.className)) {
        System.out.println("Skipping " + details.className + " because it is in openBugs.txt");
        continue;
      }
      String className = details.className.replaceAll("/", ".");
      System.out.println("testing class " + details.className);

      Class<?> sanctionedClass = null;
      try {
        sanctionedClass = Class.forName(className);
      } catch (ClassNotFoundException cnf) {
        fail(className + " cannot be found.  It may need to be removed from "
            + expectedSerializablesFileName);
      }

      if (ignoreClass(sanctionedClass)) {
        continue;
      }

      assertTrue(
          sanctionedClass.getName() + " is not Serializable and should be removed from "
              + expectedSerializablesFileName,
          Serializable.class.isAssignableFrom(sanctionedClass));

      if (Modifier.isAbstract(sanctionedClass.getModifiers())) {
        // we detect whether these are modified in another test, but cannot instantiate them.
        continue;
      }

      if (sanctionedClass.getEnclosingClass() != null
          && sanctionedClass.getEnclosingClass().isEnum()) {
        // inner enum class - enum constants are handled when we process their enclosing class
        continue;
      }

      if (sanctionedClass.isEnum()) {
        // geode enums are special cased by DataSerializer and are never java-serialized
        for (Object instance : sanctionedClass.getEnumConstants()) {
          serializeAndDeserializeSanctionedObject(instance);
        }
        continue;
      }

      Object sanctionedInstance;
      if (!Serializable.class.isAssignableFrom(sanctionedClass)) {
        throw new AssertionError(
            className + " is not serializable.  Remove it from " + expectedSerializablesFileName);
      }
      try {
        boolean isThrowable = Throwable.class.isAssignableFrom(sanctionedClass);

        Constructor<?> constructor =
            isThrowable ? sanctionedClass.getDeclaredConstructor(String.class)
                : sanctionedClass.getDeclaredConstructor((Class<?>[]) null);
        constructor.setAccessible(true);
        sanctionedInstance =
            isThrowable ? constructor.newInstance("test throwable") : constructor.newInstance();
        serializeAndDeserializeSanctionedObject(sanctionedInstance);
        continue;
      } catch (NoSuchMethodException | InstantiationException | IllegalAccessException e) {
        // fall through
      }
      try {
        Class<?> superClass = sanctionedClass;
        Constructor<?> constructor = null;
        if (Externalizable.class.isAssignableFrom(sanctionedClass)) {
          Constructor<?> cons = sanctionedClass.getDeclaredConstructor((Class<?>[]) null);
          cons.setAccessible(true);
        } else {
          while (Serializable.class.isAssignableFrom(superClass)) {
            if ((superClass = superClass.getSuperclass()) == null) {
              throw new AssertionError(
                  className + " cannot be instantiated for serialization.  Remove it from "
                      + expectedSerializablesFileName);
            }
          }
          constructor = superClass.getDeclaredConstructor((Class<?>[]) null);
          constructor.setAccessible(true);
          constructor = ReflectionFactory.getReflectionFactory()
              .newConstructorForSerialization(sanctionedClass, constructor);
        }
        sanctionedInstance = constructor.newInstance();
      } catch (Exception e) {
        throw new AssertionError("Unable to instantiate " + className + " - please move it from "
            + expectedSerializablesFileName + " to excludedClasses.txt", e);
      }
      serializeAndDeserializeSanctionedObject(sanctionedInstance);
    }
  }

  @Test
  public void testOpenBugsAreInSanctionedSerializables() throws Exception {
    loadExpectedSerializables();
    List<String> openBugs = loadOpenBugs(getResourceAsFile(OPEN_BUGS_TXT));
    Set<String> expectedSerializableClasses = new HashSet<>();

    for (ClassAndVariableDetails details : expectedSerializables) {
      expectedSerializableClasses.add(details.className);
    }

    for (String openBugClass : openBugs) {
      assertTrue(
          "open bug class: " + openBugClass + " is not present in " + expectedSerializablesFileName,
          expectedSerializableClasses.contains(openBugClass));
    }
  }

  @Test
  public void testExcludedClassesAreNotInSanctionedSerializables() throws Exception {
    loadExpectedSerializables();
    Set<String> expectedSerializableClasses = new HashSet<>();

    for (ClassAndVariableDetails details : expectedSerializables) {
      expectedSerializableClasses.add(details.className);
    }

    List<String> excludedClasses = loadExcludedClasses(getResourceAsFile(EXCLUDED_CLASSES_TXT));

    for (String excludedClass : excludedClasses) {
      assertFalse(
          "Excluded class: " + excludedClass + " was found in " + expectedSerializablesFileName,
          expectedSerializableClasses.contains(excludedClass));
    }
  }

  public void loadExpectedSerializables() throws Exception {
    if (expectedSerializablesFileName == null) {
      expectedSerializables = emptyList();
    } else {
      try (InputStream expectedSerializablesStream =
          getResourceAsStream(getModuleClass(), expectedSerializablesFileName)) {
        // the expectedSerializablesStream will be automatically closed when we exit this block
        expectedSerializables =
            CompiledClassUtils.loadClassesAndVariables(expectedSerializablesStream);
      }
    }
  }

  private List<ClassAndVariables> findSerializables() throws IOException {
    List<ClassAndVariables> result = new ArrayList<>(2000);
    List<String> excludedClasses = loadExcludedClasses(getResourceAsFile(EXCLUDED_CLASSES_TXT));
    System.out.println("excluded classes are " + excludedClasses);
    Set<String> setOfExclusions = new HashSet<>(excludedClasses);
    for (Map.Entry<String, CompiledClass> entry : classes.entrySet()) {
      CompiledClass compiledClass = entry.getValue();
      if (setOfExclusions.contains(compiledClass.fullyQualifiedName())) {
        System.out.println("excluding class " + compiledClass.fullyQualifiedName());
        continue;
      }
      // System.out.println("processing class " + compiledClass.fullyQualifiedName());

      if (!compiledClass.isInterface() && isSerializableAndNotDataSerializable(compiledClass)) {
        ClassAndVariables classAndVariables = new ClassAndVariables(compiledClass);
        for (int i = 0; i < compiledClass.fields_count; i++) {
          CompiledField compiledField = compiledClass.fields[i];
          if (!compiledField.isStatic() && !compiledField.isTransient()) {
            classAndVariables.variables.put(compiledField.name(), compiledField);
          }
        }
        result.add(classAndVariables);
      }
    }
    Collections.sort(result);
    return result;
  }

  @Override
  protected void initializeSerializationService() {
    Properties properties = new Properties();
    properties.setProperty(VALIDATE_SERIALIZABLE_OBJECTS, "true");
    properties.setProperty(SERIALIZABLE_OBJECT_FILTER, "!*");

    InternalDataSerializer.initializeSerializationFilter(new DistributionConfigImpl(properties));
  }

  @Override
  protected void deserializeObject(BufferDataOutputStream outputStream)
      throws IOException, ClassNotFoundException {
    DataSerializer
        .readObject(new DataInputStream(new ByteArrayInputStream(outputStream.toByteArray())));
  }

  @Override
  protected void serializeObject(Object object, BufferDataOutputStream outputStream)
      throws IOException {
    DataSerializer.writeObject(object, outputStream);
  }

  private void serializeAndDeserializeSanctionedObject(Object object) throws Exception {
    BufferDataOutputStream outputStream = new BufferDataOutputStream(KnownVersion.CURRENT);
    try {
      serializeObject(object, outputStream);
    } catch (RemoteException e) {
      fail(object.getClass().getName() +
          " is a java.rmi.server.RemoteObject which is not supported by AnalyzeSerializables", e);
    } catch (IOException e) {
      // some classes, such as BackupLock, are Serializable because the extend something
      // like ReentrantLock but we never serialize them & it doesn't work to try to do so
      throw new AssertionError("Not Serializable: " + object.getClass().getName(), e);
    }
    try {
      deserializeObject(outputStream);
    } catch (CancelException e) {
      // PDX classes fish for a PDXRegistry and find that there is no cache
    } catch (InvalidClassException e) {
      fail("I was unable to deserialize " + object.getClass().getName(), e);
    }
  }

  private boolean isSerializableAndNotDataSerializable(CompiledClass compiledClass) {
    // these classes throw exceptions or log ugly messages when you try to load them
    // in junit
    String name = compiledClass.fullyQualifiedName().replace('/', '.');
    if (name.startsWith("org.apache.geode.internal.shared.NativeCallsJNAImpl")
        || name.startsWith("org.apache.geode.internal.statistics.HostStatHelper")) {
      return false;
    }
    try {
      Class<?> realClass = Class.forName(name);
      return Serializable.class.isAssignableFrom(realClass)
          && !DataSerializable.class.isAssignableFrom(realClass)
          && !DataSerializableFixedID.class.isAssignableFrom(realClass);
    } catch (UnsatisfiedLinkError e) {
      System.out.println("Unable to load actual class " + name + " external JNI dependencies");
    } catch (NoClassDefFoundError e) {
      System.out.println("Unable to load actual class " + name + " not in JUnit classpath");
    } catch (Throwable e) {
      System.out.println("Unable to load actual class " + name + ": " + e);
    }
    return false;
  }
}
