/*
 * Copyright 2021 EPAM Systems, Inc
 *
 * See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. Licensed under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.epam.deltix.qsrv.hf.tickdb.lang.compiler.sx;

import com.epam.deltix.qsrv.hf.pub.md.*;

/**
 *
 */
public class TypeCheck extends CompiledComplexExpression {
    public final ClassDescriptor           checkType;

    public TypeCheck (CompiledExpression arg, ClassDescriptor targetType) {
        super (
            arg.type.isNullable () ?
                StandardTypes.NULLABLE_BOOLEAN : 
                StandardTypes.CLEAN_BOOLEAN, 
            arg
        );

        this.checkType = targetType;
    }

    @Override
    @SuppressWarnings ("EqualsWhichDoesntCheckParameterClass")
    public boolean                  equals (Object obj) {
        return
            super.equals (obj) && checkType.equals (((TypeCheck) obj).checkType);
    }

    @Override
    public int                      hashCode () {
        return super.hashCode () + checkType.hashCode ();
    }

    @Override
    protected void                  print (StringBuilder out) {
        printArgs (out);
        out.append (" is ");
        out.append (checkType.getName ());
    }
}
