/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.idea.codenavigation;

import com.intellij.openapi.util.text.StringUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

public final class CodeLocation {
  public static final int INVALID_LINE_NUMBER = -1;

  @Nullable
  private final String myClassName;

  @Nullable
  private final String myFileName;

  @Nullable
  private final String myMethodName;

  /**
   * See {@link Builder#setMethodSignature(String)} for details about this field.
   */
  @Nullable
  private final String mySignature;

  /**
   * See {@link Builder#setMethodParameters(List)} (String)} for details about this field.
   */
  @Nullable
  private final List<String> myMethodParameters;

  private final int myLineNumber;

  private final boolean myNativeCode;
  /**
   * See {@link Builder#setNativeVAddress(long)} for details about this field.
   */
  private final long myNativeVAddress;

  @Nullable
  private final String myNativeModuleName;
  /** Fully qualified name of a Composable (e.g. androidx.compose.runtime.LaunchedEffect) */
  @Nullable
  private final String myFullComposableName;

  private CodeLocation(@NotNull Builder builder) {
    myClassName = builder.myClassName;
    myFileName = builder.myFileName;
    myMethodName = builder.myMethodName;
    mySignature = builder.mySignature;
    myMethodParameters = builder.myMethodParameters;
    myLineNumber = builder.myLineNumber;
    myNativeCode = builder.myNativeCode;
    myNativeVAddress = builder.myNativeVAddress;
    myNativeModuleName = builder.myNativeModuleName;
    myFullComposableName = builder.myFullComposableName;
  }

  @TestOnly
  @NotNull
  public static CodeLocation stub() {
    return new CodeLocation.Builder("").build();
  }

  @Nullable
  public String getClassName() {
    return myClassName;
  }

  @Nullable
  public String getFileName() {
    return myFileName;
  }

  @Nullable
  public String getMethodName() {
    return myMethodName;
  }

  public int getLineNumber() {
    return myLineNumber;
  }

  /**
   * See {@link Builder#setMethodSignature(String)} for details about this value.
   */
  @Nullable
  public String getSignature() {
    return mySignature;
  }

  @Nullable
  public List<String> getMethodParameters() {
    return myMethodParameters == null ? null : new ArrayList<>(myMethodParameters);
  }

  public boolean isNativeCode() {
    return myNativeCode;
  }

  public long getNativeVAddress() {
    return myNativeVAddress;
  }

  @Nullable
  public String getNativeModuleName() {
    return myNativeModuleName;
  }

  /** Fully qualified name of a Composable (e.g. androidx.compose.runtime.LaunchedEffect) */
  @Nullable
  public String getFullComposableName() {
    return myFullComposableName;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    CodeLocation location = (CodeLocation)o;
    return myLineNumber == location.myLineNumber &&
           myNativeCode == location.myNativeCode &&
           myNativeVAddress == location.myNativeVAddress &&
           Objects.equals(myClassName, location.myClassName) &&
           Objects.equals(myFileName, location.myFileName) &&
           Objects.equals(myMethodName, location.myMethodName) &&
           Objects.equals(mySignature, location.mySignature) &&
           Objects.equals(myMethodParameters, location.myMethodParameters) &&
           Objects.equals(myNativeModuleName, location.myNativeModuleName) &&
           Objects.equals(myFullComposableName, location.myFullComposableName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(myClassName,
                        myFileName,
                        myMethodName,
                        mySignature,
                        myMethodParameters,
                        myLineNumber,
                        myNativeCode,
                        myNativeVAddress,
                        myNativeModuleName,
                        myFullComposableName);
  }

  public static final class Builder {
    @Nullable private final String myClassName;
    @Nullable String myFileName;
    @Nullable String myMethodName;
    @Nullable String mySignature;
    @Nullable List<String> myMethodParameters;
    int myLineNumber = INVALID_LINE_NUMBER;
    boolean myNativeCode;
    long myNativeVAddress = -1;
    @Nullable String myNativeModuleName;
    @Nullable String myFullComposableName;

    public Builder(@Nullable String className) {
      myClassName = className;
    }

    public Builder(@NotNull CodeLocation rhs) {
      myClassName = rhs.getClassName();
      myFileName = rhs.getFileName();
      myMethodName = rhs.getMethodName();
      mySignature = rhs.getSignature();
      myMethodParameters = rhs.getMethodParameters();
      myLineNumber = rhs.getLineNumber();
      myNativeCode = rhs.myNativeCode;
      myNativeVAddress = rhs.myNativeVAddress;
      myNativeModuleName = rhs.myNativeModuleName;
      myFullComposableName = rhs.myFullComposableName;
    }

    @NotNull
    public Builder setFileName(@Nullable String fileName) {
      myFileName = StringUtil.nullize(fileName); // "" is an invalid name and should be converted to null
      return this;
    }

    @NotNull
    public Builder setMethodName(@Nullable String methodName) {
      myMethodName = StringUtil.nullize(methodName); // "" is an invalid name and should be converted to null
      return this;
    }

    /**
     * Signature of a method, encoded by java type encoding
     * <p>
     * For example:
     * {@code int aMethod(List<String> a, ArrayList<T> b, boolean c, Integer[][] d)}
     * the signature is (Ljava/util/List;Ljava/util/ArrayList;Z[[Ljava/lang/Integer;)I
     * <p>
     * Java encoding: https://docs.oracle.com/javase/7/docs/api/java/lang/Class.html#getName()
     */
    @NotNull
    public Builder setMethodSignature(@NotNull String signature) {
      mySignature = signature;
      return this;
    }

    /**
     * Parameters of a method or function.
     * <p>
     * For example, {@code int aMethod(int a, float b} produces {@code ["int", "float"]} as the list of parameters.
     */
    @NotNull
    public Builder setMethodParameters(@NotNull List<String> methodParameters) {
      myMethodParameters = methodParameters;
      return this;
    }

    /**
     * @param lineNumber 0-based line number.
     */
    @NotNull
    public Builder setLineNumber(int lineNumber) {
      myLineNumber = lineNumber;
      return this;
    }

    public Builder setNativeCode(boolean nativeCode) {
      myNativeCode = nativeCode;
      return this;
    }

    /**
     * Virtual address of the instruction corresponding to this code location in the ELF file containing it.
     */
    public Builder setNativeVAddress(long nativeVAddress) {
      myNativeVAddress = nativeVAddress;
      return this;
    }

    /**
     * Name of a native library (like libunity.so).
     */
    public Builder setNativeModuleName(String name) {
      myNativeModuleName = name;
      return this;
    }

    /** Fully qualified name of a Composable (e.g. androidx.compose.runtime.LaunchedEffect) */
    public Builder setFullComposableName(String name) {
      myFullComposableName = name;
      return this;
    }

    @NotNull
    public CodeLocation build() {
      return new CodeLocation(this);
    }
  }

  /**
   * Convenience method for stripping all inner classes (e.g. anything following the first "$")
   * from a Java class name. If it is already the outer class name, the class name is returned
   * as-is.
   */
  @NotNull
  public String getOuterClass() {
    if (myClassName == null) return "";

    int innerCharIndex = myClassName.indexOf('$');
    return innerCharIndex < 0 ? myClassName : myClassName.substring(0, innerCharIndex);
  }
}
