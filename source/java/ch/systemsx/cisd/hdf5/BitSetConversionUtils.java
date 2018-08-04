/*
 * Copyright 2007 - 2018 ETH Zuerich, CISD and SIS.
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

import java.lang.reflect.Field;
import java.util.BitSet;

import hdf.hdf5lib.exceptions.HDF5JavaException;

import org.apache.commons.lang.SystemUtils;

//import ch.rinn.restrictions.Private;
import ch.systemsx.cisd.base.mdarray.MDLongArray;

/**
 * Methods for converting {@link BitSet}s to a storage form suitable for storing in an HDF5 file.
 * <p>
 * <i>This is an internal API that should not be expected to be stable between releases!</i>
 * 
 * @author Bernd Rinn
 */
public final class BitSetConversionUtils
{
    private final static int ADDRESS_BITS_PER_WORD = 6;

    private final static int BITS_PER_WORD = 1 << ADDRESS_BITS_PER_WORD;

    private final static int BIT_INDEX_MASK = BITS_PER_WORD - 1;

    private static final Field BIT_SET_WORDS = getBitSetWords();

    private static final Field BIT_SET_WORDS_IN_USE = getBitSetWordsInUse();

    private static Field getBitSetWords()
    {
        try
        {
            final Field bitsField =
                    BitSet.class.getDeclaredField(SystemUtils.IS_JAVA_1_5 ? "bits" : "words");
            bitsField.setAccessible(true);
            return bitsField;
        } catch (final NoSuchFieldException ex)
        {
            return null;
        }
    }

    private static Field getBitSetWordsInUse()
    {
        try
        {
            final Field unitsInUseField =
                    BitSet.class.getDeclaredField(SystemUtils.IS_JAVA_1_5 ? "unitsInUse"
                            : "wordsInUse");
            unitsInUseField.setAccessible(true);
            return unitsInUseField;
        } catch (final NoSuchFieldException ex)
        {
            return null;
        }
    }

    public static BitSet fromStorageForm(final long[] serializedWordArray)
    {
        return fromStorageForm(serializedWordArray, 0, serializedWordArray.length);
    }
    
    public static BitSet fromStorageForm(final long[] serializedWordArray, int start, int length)
    {
        if (BIT_SET_WORDS != null)
        {
            return fromStorageFormFast(serializedWordArray, start, length);
        } else
        {
            return fromStorageFormGeneric(serializedWordArray, start, length);
        }
    }

    public static BitSet[] fromStorageForm2D(final MDLongArray serializedWordArray)
    {
        if (serializedWordArray.rank() != 2)
        {
            throw new HDF5JavaException("Array is supposed to be of rank 2, but is of rank "
                    + serializedWordArray.rank());
        }
        final int dimX = serializedWordArray.dimensions()[0];
        final int dimY = serializedWordArray.dimensions()[1];
        final BitSet[] result = new BitSet[dimY];
        for (int i = 0; i < result.length; ++i)
        {
            result[i] = fromStorageForm(serializedWordArray.getAsFlatArray(), i * dimX, dimX);
        }
        return result;
    }

    private static BitSet fromStorageFormFast(final long[] serializedWordArray, int start, int length)
    {
        try
        {
            final BitSet result = new BitSet();
            int inUse = calcInUse(serializedWordArray, start, length);
            BIT_SET_WORDS_IN_USE.set(result, inUse);
            BIT_SET_WORDS.set(result, trim(serializedWordArray, start, inUse));
            return result;
        } catch (final IllegalAccessException ex)
        {
            throw new IllegalAccessError(ex.getMessage());
        }
    }

    //@Private
    static BitSet fromStorageFormGeneric(final long[] serializedWordArray, int start, int length)
    {
        final BitSet result = new BitSet();
        for (int wordIndex = 0; wordIndex < length; ++wordIndex)
        {
            final long word = serializedWordArray[start + wordIndex];
            for (int bitInWord = 0; bitInWord < BITS_PER_WORD; ++bitInWord)
            {
                if ((word & 1L << bitInWord) != 0)
                {
                    result.set(wordIndex << ADDRESS_BITS_PER_WORD | bitInWord);
                }
            }
        }
        return result;
    }

    public static long[] toStorageForm(final BitSet[] data, int numberOfWords)
    {
        final long[] result = new long[data.length * numberOfWords];
        int idx = 0;
        for (BitSet bs : data)
        {
            System.arraycopy(toStorageForm(bs, numberOfWords), 0, result, idx, numberOfWords);
            idx += numberOfWords;
        }
        return result;
    }

    public static long[] toStorageForm(final BitSet data)
    {
        if (BIT_SET_WORDS != null)
        {
            return toStorageFormFast(data);
        } else
        {
            return toStorageFormGeneric(data);
        }
    }

    public static long[] toStorageForm(final BitSet data, int numberOfWords)
    {
        if (BIT_SET_WORDS != null)
        {
            return toStorageFormFast(data, numberOfWords);
        } else
        {
            return toStorageFormGeneric(data, numberOfWords);
        }
    }

    private static long[] toStorageFormFast(final BitSet data)
    {
        try
        {
            long[] storageForm = (long[]) BIT_SET_WORDS.get(data);
            int inUse = BIT_SET_WORDS_IN_USE.getInt(data);
            return trim(storageForm, 0, inUse);
        } catch (final IllegalAccessException ex)
        {
            throw new IllegalAccessError(ex.getMessage());
        }
    }

    private static long[] toStorageFormFast(final BitSet data, int numberOfWords)
    {
        try
        {
            long[] storageForm = (long[]) BIT_SET_WORDS.get(data);
            return trimEnforceLen(storageForm, 0, numberOfWords);
        } catch (final IllegalAccessException ex)
        {
            throw new IllegalAccessError(ex.getMessage());
        }
    }

    private static long[] trim(final long[] array, int start, int len)
    {
        final int inUse = calcInUse(array, start, len);
        if (inUse < array.length)
        {
            final long[] trimmedArray = new long[inUse];
            System.arraycopy(array, start, trimmedArray, 0, inUse);
            return trimmedArray;
        }
        return array;
    }

    private static long[] trimEnforceLen(final long[] array, int start, int len)
    {
        if (len != array.length)
        {
            final long[] trimmedArray = new long[len];
            final int inUse = calcInUse(array, start, len);
            System.arraycopy(array, start, trimmedArray, 0, inUse);
            return trimmedArray;
        }
        return array;
    }

    private static int calcInUse(final long[] array, int start, int len)
    {
        int result = Math.min(len, array.length);
        while (result > 0 && array[start + result - 1] == 0)
        {
            --result;
        }
        return result;
    }

    /**
     * Given a bit index return the word index containing it.
     */
    public static int getWordIndex(final int bitIndex)
    {
        return bitIndex >> ADDRESS_BITS_PER_WORD;
    }

    /**
     * Given a bit index, return a unit that masks that bit in its unit.
     */
    public static long getBitMaskInWord(final int bitIndex)
    {
        return 1L << (bitIndex & BIT_INDEX_MASK);
    }

    // @Private
    static long[] toStorageFormGeneric(final BitSet data)
    {
        final long[] words = new long[data.size() >> ADDRESS_BITS_PER_WORD];
        for (int bitIndex = data.nextSetBit(0); bitIndex >= 0; bitIndex =
                data.nextSetBit(bitIndex + 1))
        {
            final int wordIndex = getWordIndex(bitIndex);
            words[wordIndex] |= getBitMaskInWord(bitIndex);
        }
        return words;
    }

    // @Private
    static long[] toStorageFormGeneric(final BitSet data, final int numberOfWords)
    {
        final long[] words = new long[numberOfWords];
        for (int bitIndex = data.nextSetBit(0); bitIndex >= 0; bitIndex =
                data.nextSetBit(bitIndex + 1))
        {
            final int wordIndex = getWordIndex(bitIndex);
            if (wordIndex >= words.length)
            {
                break;
            }
            words[wordIndex] |= getBitMaskInWord(bitIndex);
        }
        return words;
    }

    static int getMaxLength(BitSet[] data)
    {
        int length = 0;
        for (BitSet bs : data)
        {
            length = Math.max(length, bs.length());
        }
        return length;
    }

}
