/*
 * Copyright 2016 higherfrequencytrading.com
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

package net.openhft.chronicle.bytes.util;

import net.openhft.chronicle.bytes.AppendableUtil;
import net.openhft.chronicle.bytes.BytesStore;
import net.openhft.chronicle.bytes.UTFDataFormatRuntimeException;
import net.openhft.chronicle.core.pool.StringBuilderPool;
import org.jetbrains.annotations.NotNull;

/**
 * @author peter.lawrey
 */
public class UTF8StringInterner extends AbstractInterner<String> {

    private static final StringBuilderPool SBP = new StringBuilderPool();

    public UTF8StringInterner(int capacity) throws IllegalArgumentException {
        super(capacity);
    }

    @Override
    @NotNull
    protected String getValue(@NotNull BytesStore cs, int length) throws UTFDataFormatRuntimeException {
        //System.out.println("UTF8StringInterner.getValue(cs, length:"+length+")");
        StringBuilder sb = SBP.acquireStringBuilder();
        AppendableUtil.parseUtf8(cs, sb, length);
        //System.out.println("UTF8StringInterner.getValue(-):" + sb.toString());
        return sb.toString();
    }
}
