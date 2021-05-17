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
package com.epam.deltix.qsrv.solgen.cpp;

import com.epam.deltix.qsrv.solgen.CodegenUtils;
import com.epam.deltix.qsrv.solgen.SolgenUtils;
import com.epam.deltix.qsrv.solgen.StreamMetaData;
import com.epam.deltix.qsrv.solgen.base.*;

import java.util.*;

public class ReadStreamSample extends CppSample {

    static final List<Property> PROPERTIES = Collections.unmodifiableList(Arrays.asList(CppSampleFactory.STREAM_KEY));

    private static final String CODEC_INCLUDE = "cpp.codec.include";
    private static final String CODEC_CLASS_NAME = "cpp.codec.className";

    private static final String SAMPLE_NAME = "read-stream";
    private static final String SCRIPT_NAME = SAMPLE_NAME + ".cpp";
    private static final String TEMPLATE = SAMPLE_NAME + ".cpp-template";

    private final Source source;

    public ReadStreamSample(Properties properties) {
        this(properties.getProperty(CppSampleFactory.TB_URL.getName()),
            properties.getProperty(CppSampleFactory.STREAM_KEY.getName())
        );
    }

    public ReadStreamSample(String tbUrl, String stream) {
        super("readstream", stream);
        Map<String, String> params = new HashMap<>();
        params.put(CppSampleFactory.TB_URL.getName(), tbUrl);
        params.put(CppSampleFactory.STREAM_KEY.getName(), stream);

        StreamMetaData metaData = CodegenUtils.getStreamMetadata(tbUrl, stream);

        params.put(
            CODEC_INCLUDE,
            "#include \"codecs/" +
            metaData.getNameSpace() + "/" +
            CppCodecGenerator.getStreamCodecName(metaData.getNameSpace(), null) +
            ".h\""
        );
        params.put(
            CODEC_CLASS_NAME,
            CppCodecGenerator.getStreamDecoder(metaData.getNameSpace(), metaData.getNameSpace())
        );

        source = new StringSource(
            SCRIPT_NAME,
            SolgenUtils.readTemplateFromClassPath(this.getClass().getPackage(), TEMPLATE, params)
        );

    }

    @Override
    public void addToProject(Project project) {
        super.addToProject(project);

        project.addSource(source);
    }

}
