/*
 * Copyright 2011 ETH Zuerich, CISD
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

package ch.systemsx.cisd.hdf5;

import java.io.UnsupportedEncodingException;

/**
 * Some auxiliary methods for String to Byte conversion.
 * 
 * @author Bernd Rinn
 */
public final class StringUtils
{
    private StringUtils()
    {
        // Not to be instantiated.
    }

    /**
     * Converts string <var>s</var> to a byte array of a 0-terminated string, using
     * <var>encoding</var> and cutting it to <var>maxLength</var< if necessary.
     */
    public static byte[] toBytes0Term(String s, int maxLength, CharacterEncoding encoding)
    {
        try
        {
            return (cut(s, maxLength) + '\0').getBytes(encoding.getCharSetName());
        } catch (UnsupportedEncodingException ex)
        {
            return (cut(s, maxLength) + '\0').getBytes();
        }
    }

    /**
     * Converts string array <var>in</var> to a byte array of a 0-terminated string, using
     * <var>encoding</var> and cutting it to <var>maxLength</var< if necessary.
     */
    public static byte[] toBytes0Term(final String[] in, final int maxLength,
            final CharacterEncoding encoding)
    {
        final int nelems = in.length;
        final int realMaxLength = (encoding == CharacterEncoding.UTF8 ? 2 : 1) * maxLength + 1;
        final byte[] out = new byte[nelems * realMaxLength];

        for (int i = 0; i < nelems; i++)
        {
            final byte[] bytes = toBytes0Term(in[i], maxLength, encoding);
            System.arraycopy(bytes, 0, out, i * realMaxLength, bytes.length);
        }
        return out;
    }

    /**
     * Converts byte array <var>data</var> containing a 0-terminated string using
     * <var>encoding</var> to a string.
     */
    public static String fromBytes0Term(byte[] data, CharacterEncoding encoding)
    {
        return fromBytes0Term(data, 0, data.length, encoding);
    }

    /**
     * Converts byte array <var>data</var> containing a 0-terminated string at <var>startIdx</var>
     * using <var>encoding</var> to a string. Does search further than <var>maxEndIdx</var>
     */
    public static String fromBytes0Term(byte[] data, int startIdx, int maxEndIdx,
            CharacterEncoding encoding)
    {
        int termIdx;
        for (termIdx = startIdx; termIdx < maxEndIdx && data[termIdx] != 0; ++termIdx)
        {
        }
        try
        {
            return new String(data, startIdx, termIdx - startIdx, encoding.getCharSetName());
        } catch (UnsupportedEncodingException ex)
        {
            return new String(data, startIdx, termIdx - startIdx);
        }
    }

    private static String cut(String s, int maxLength)
    {
        if (s.length() > maxLength)
        {
            return s.substring(0, maxLength);
        } else
        {
            return s;
        }
    }

}