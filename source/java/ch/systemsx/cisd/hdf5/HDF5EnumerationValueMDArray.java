/*
 * Copyright 20012 ETH Zuerich, CISD
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

import java.util.Iterator;

import ch.systemsx.cisd.base.convert.NativeData;
import ch.systemsx.cisd.base.convert.NativeData.ByteOrder;
import ch.systemsx.cisd.base.mdarray.MDAbstractArray;
import ch.systemsx.cisd.base.mdarray.MDArray;
import ch.systemsx.cisd.base.mdarray.MDByteArray;
import ch.systemsx.cisd.base.mdarray.MDIntArray;
import ch.systemsx.cisd.base.mdarray.MDShortArray;
import ch.systemsx.cisd.hdf5.HDF5EnumerationType.StorageFormEnum;
import ch.systemsx.cisd.hdf5.hdf5lib.HDFNativeData;

/**
 * A class the represents a multi-dimensional array of HDF enumeration values.
 * 
 * @author Bernd Rinn
 */
public class HDF5EnumerationValueMDArray implements Iterable<MDArray<String>.ArrayEntry>
{
    private final HDF5EnumerationType type;

    private final int size;

    private StorageFormEnum storageForm;

    private MDByteArray bArrayOrNull;

    private MDShortArray sArrayOrNull;

    private MDIntArray iArrayOrNull;

    HDF5EnumerationValueMDArray(HDF5EnumerationType type, MDAbstractArray<?> array)
            throws IllegalArgumentException
    {
        this.type = type;
        this.size = array.size();
        if (array instanceof MDByteArray)
        {
            final MDByteArray bArray = (MDByteArray) array;
            setOrdinalArray(bArray);
        } else if (array instanceof MDShortArray)
        {
            final MDShortArray sArray = (MDShortArray) array;
            setOrdinalArray(sArray);
        } else if (array instanceof MDIntArray)
        {
            final MDIntArray iArray = (MDIntArray) array;
            setOrdinalArray(iArray);
        } else if (array instanceof MDArray)
        {
            final MDArray<?> concreteArray = (MDArray<?>) array;
            if (concreteArray.getAsFlatArray().getClass().getComponentType() == String.class)
            {
                @SuppressWarnings("unchecked")
                final MDArray<String> sArray = (MDArray<String>) concreteArray;
                map(sArray);
            } else if (concreteArray.getAsFlatArray().getClass().getComponentType().isEnum())
            {
                @SuppressWarnings("unchecked")
                final MDArray<Enum<?>> eArray = (MDArray<Enum<?>>) concreteArray;
                map(toString(eArray));
            } else
            {
                throw new IllegalArgumentException("array has illegal component type "
                        + concreteArray.getAsFlatArray().getClass().getComponentType()
                                .getCanonicalName());
            }
        } else
        {
            throw new IllegalArgumentException("array is of illegal type "
                    + array.getClass().getCanonicalName());
        }
    }

    /**
     * Creates an enumeration value array.
     * 
     * @param type The enumeration type of this value.
     * @param ordinalArray The array of ordinal values in the <var>type</var>.
     * @throws IllegalArgumentException If any of the ordinals in the <var>ordinalArray</var> is
     *             outside of the range of allowed values of the <var>type</var>.
     */
    public HDF5EnumerationValueMDArray(HDF5EnumerationType type, MDByteArray ordinalArray)
            throws IllegalArgumentException
    {
        this.type = type;
        this.size = ordinalArray.size();
        setOrdinalArray(ordinalArray);
    }

    /**
     * Creates an enumeration value array.
     * 
     * @param type The enumeration type of this value.
     * @param ordinalArray The array of ordinal values in the <var>type</var>.
     * @throws IllegalArgumentException If any of the ordinals in the <var>ordinalArray</var> is
     *             outside of the range of allowed values of the <var>type</var>.
     */
    public HDF5EnumerationValueMDArray(HDF5EnumerationType type, MDShortArray ordinalArray)
            throws IllegalArgumentException
    {
        this.type = type;
        this.size = ordinalArray.size();
        setOrdinalArray(ordinalArray);
    }

    /**
     * Creates an enumeration value array.
     * 
     * @param type The enumeration type of this value.
     * @param ordinalArray The array of ordinal values in the <var>type</var>.
     * @throws IllegalArgumentException If any of the ordinals in the <var>ordinalArray</var> is
     *             outside of the range of allowed values of the <var>type</var>.
     */
    public HDF5EnumerationValueMDArray(HDF5EnumerationType type, MDIntArray ordinalArray)
            throws IllegalArgumentException
    {
        this.type = type;
        this.size = ordinalArray.size();
        setOrdinalArray(ordinalArray);
    }

    private static MDArray<String> toString(MDArray<Enum<?>> valueArray)
    {
        final Enum<?>[] flatEnumArray = valueArray.getAsFlatArray();
        final MDArray<String> result = new MDArray<String>(String.class, valueArray.dimensions());
        final String[] flatStringArray = result.getAsFlatArray();
        for (int i = 0; i < flatEnumArray.length; ++i)
        {
            flatStringArray[i] = flatEnumArray[i].name();
        }
        return result;
    }

    private void map(MDArray<String> array) throws IllegalArgumentException
    {
        final String[] flatArray = array.getAsFlatArray();
        if (type.getValueArray().length < Byte.MAX_VALUE)
        {
            storageForm = StorageFormEnum.BYTE;
            bArrayOrNull = new MDByteArray(array.dimensions());
            final byte[] flatBArray = bArrayOrNull.getAsFlatArray();
            for (int i = 0; i < flatArray.length; ++i)
            {
                final Integer indexOrNull = type.tryGetIndexForValue(flatArray[i]);
                if (indexOrNull == null)
                {
                    throw new IllegalArgumentException("Value '" + flatArray[i]
                            + "' is not allowed for type '" + type.getName() + "'.");
                }
                flatBArray[i] = indexOrNull.byteValue();
            }
            sArrayOrNull = null;
            iArrayOrNull = null;
        } else if (type.getValueArray().length < Short.MAX_VALUE)
        {
            storageForm = StorageFormEnum.SHORT;
            bArrayOrNull = null;
            sArrayOrNull = new MDShortArray(array.dimensions());
            final short[] flatSArray = sArrayOrNull.getAsFlatArray();
            for (int i = 0; i < flatArray.length; ++i)
            {
                final Integer indexOrNull = type.tryGetIndexForValue(flatArray[i]);
                if (indexOrNull == null)
                {
                    throw new IllegalArgumentException("Value '" + flatArray[i]
                            + "' is not allowed for type '" + type.getName() + "'.");
                }
                flatSArray[i] = indexOrNull.shortValue();
            }
            iArrayOrNull = null;
        } else
        {
            storageForm = StorageFormEnum.INT;
            bArrayOrNull = null;
            sArrayOrNull = null;
            iArrayOrNull = new MDIntArray(array.dimensions());
            final int[] flatIArray = iArrayOrNull.getAsFlatArray();
            for (int i = 0; i < flatIArray.length; ++i)
            {
                final Integer indexOrNull = type.tryGetIndexForValue(flatArray[i]);
                if (indexOrNull == null)
                {
                    throw new IllegalArgumentException("Value '" + flatArray[i]
                            + "' is not allowed for type '" + type.getName() + "'.");
                }
                flatIArray[i] = indexOrNull.intValue();
            }
        }
    }

    private void setOrdinalArray(MDByteArray array)
    {
        if (type.getValueArray().length < Byte.MAX_VALUE)
        {
            storageForm = StorageFormEnum.BYTE;
            bArrayOrNull = array;
            checkOrdinalArray(bArrayOrNull);
            sArrayOrNull = null;
            iArrayOrNull = null;
        } else if (type.getValueArray().length < Short.MAX_VALUE)
        {
            storageForm = StorageFormEnum.SHORT;
            bArrayOrNull = null;
            sArrayOrNull = toShortArray(array);
            checkOrdinalArray(sArrayOrNull);
            iArrayOrNull = null;
        } else
        {
            storageForm = StorageFormEnum.INT;
            bArrayOrNull = null;
            sArrayOrNull = null;
            iArrayOrNull = toIntArray(array);
            checkOrdinalArray(iArrayOrNull);
        }
    }

    private void setOrdinalArray(MDShortArray array) throws IllegalArgumentException
    {
        if (type.getValueArray().length < Byte.MAX_VALUE)
        {
            storageForm = StorageFormEnum.BYTE;
            bArrayOrNull = toByteArray(array);
            checkOrdinalArray(bArrayOrNull);
            sArrayOrNull = null;
            iArrayOrNull = null;
        } else if (type.getValueArray().length < Short.MAX_VALUE)
        {
            storageForm = StorageFormEnum.SHORT;
            bArrayOrNull = null;
            sArrayOrNull = array;
            checkOrdinalArray(sArrayOrNull);
            iArrayOrNull = null;
        } else
        {
            storageForm = StorageFormEnum.INT;
            bArrayOrNull = null;
            sArrayOrNull = null;
            iArrayOrNull = toIntArray(array);
            checkOrdinalArray(iArrayOrNull);
        }
    }

    private void setOrdinalArray(MDIntArray array) throws IllegalArgumentException
    {
        if (type.getValueArray().length < Byte.MAX_VALUE)
        {
            storageForm = StorageFormEnum.BYTE;
            bArrayOrNull = toByteArray(array);
            checkOrdinalArray(bArrayOrNull);
            sArrayOrNull = null;
            iArrayOrNull = null;
        } else if (type.getValueArray().length < Short.MAX_VALUE)
        {
            storageForm = StorageFormEnum.SHORT;
            bArrayOrNull = null;
            sArrayOrNull = toShortArray(array);
            checkOrdinalArray(sArrayOrNull);
            iArrayOrNull = null;
        } else
        {
            storageForm = StorageFormEnum.INT;
            bArrayOrNull = null;
            sArrayOrNull = null;
            iArrayOrNull = array;
            checkOrdinalArray(iArrayOrNull);
        }
    }

    private MDByteArray toByteArray(MDShortArray array) throws IllegalArgumentException
    {
        final short[] flatSourceArray = array.getAsFlatArray();
        final MDByteArray bArray = new MDByteArray(array.dimensions());
        final byte[] flatTargetArray = bArray.getAsFlatArray();
        for (int i = 0; i < flatSourceArray.length; ++i)
        {
            flatTargetArray[i] = (byte) flatSourceArray[i];
            if (flatTargetArray[i] != flatSourceArray[i])
            {
                throw new IllegalArgumentException("Value " + flatSourceArray[i]
                        + " cannot be stored in byte array");
            }
        }
        return bArray;
    }

    private MDByteArray toByteArray(MDIntArray array) throws IllegalArgumentException
    {
        final int[] flatSourceArray = array.getAsFlatArray();
        final MDByteArray bArray = new MDByteArray(array.dimensions());
        final byte[] flatTargetArray = bArray.getAsFlatArray();
        for (int i = 0; i < flatSourceArray.length; ++i)
        {
            flatTargetArray[i] = (byte) flatSourceArray[i];
            if (flatTargetArray[i] != flatSourceArray[i])
            {
                throw new IllegalArgumentException("Value " + flatSourceArray[i]
                        + " cannot be stored in byte array");
            }
        }
        return bArray;
    }

    private MDShortArray toShortArray(MDByteArray array)
    {
        final byte[] flatSourceArray = array.getAsFlatArray();
        final MDShortArray sArray = new MDShortArray(array.dimensions());
        final short[] flatTargetArray = sArray.getAsFlatArray();
        for (int i = 0; i < flatSourceArray.length; ++i)
        {
            flatTargetArray[i] = flatSourceArray[i];
        }
        return sArray;
    }

    private MDShortArray toShortArray(MDIntArray array) throws IllegalArgumentException
    {
        final int[] flatSourceArray = array.getAsFlatArray();
        final MDShortArray sArray = new MDShortArray(array.dimensions());
        final short[] flatTargetArray = sArray.getAsFlatArray();
        for (int i = 0; i < flatSourceArray.length; ++i)
        {
            flatTargetArray[i] = (short) flatSourceArray[i];
            if (flatSourceArray[i] != flatTargetArray[i])
            {
                throw new IllegalArgumentException("Value " + flatSourceArray[i]
                        + " cannot be stored in short array");
            }
        }
        return sArray;
    }

    private MDIntArray toIntArray(MDByteArray array)
    {
        final byte[] flatSourceArray = array.getAsFlatArray();
        final MDIntArray iArray = new MDIntArray(array.dimensions());
        final int[] flatTargetArray = iArray.getAsFlatArray();
        for (int i = 0; i < flatSourceArray.length; ++i)
        {
            flatTargetArray[i] = flatSourceArray[i];
        }
        return iArray;
    }

    private MDIntArray toIntArray(MDShortArray array)
    {
        final short[] flatSourceArray = array.getAsFlatArray();
        final MDIntArray iArray = new MDIntArray(array.dimensions());
        final int[] flatTargetArray = iArray.getAsFlatArray();
        for (int i = 0; i < flatSourceArray.length; ++i)
        {
            flatTargetArray[i] = flatSourceArray[i];
        }
        return iArray;
    }

    private void checkOrdinalArray(MDByteArray array) throws IllegalArgumentException
    {
        final byte[] flatArray = array.getAsFlatArray();
        for (int i = 0; i < flatArray.length; ++i)
        {
            if (flatArray[i] < 0 || flatArray[i] >= type.getValueArray().length)
            {
                throw new IllegalArgumentException("valueIndex " + flatArray[i]
                        + " out of allowed range [0.." + (type.getValueArray().length - 1)
                        + "] of type '" + type.getName() + "'.");
            }
        }
    }

    private void checkOrdinalArray(MDShortArray array) throws IllegalArgumentException
    {
        final short[] flatArray = array.getAsFlatArray();
        for (int i = 0; i < flatArray.length; ++i)
        {
            if (flatArray[i] < 0 || flatArray[i] >= type.getValueArray().length)
            {
                throw new IllegalArgumentException("valueIndex " + flatArray[i]
                        + " out of allowed range [0.." + (type.getValueArray().length - 1)
                        + "] of type '" + type.getName() + "'.");
            }
        }
    }

    private void checkOrdinalArray(MDIntArray array) throws IllegalArgumentException
    {
        final int[] flatArray = array.getAsFlatArray();
        for (int i = 0; i < flatArray.length; ++i)
        {
            if (flatArray[i] < 0 || flatArray[i] >= type.getValueArray().length)
            {
                throw new IllegalArgumentException("valueIndex " + flatArray[i]
                        + " out of allowed range [0.." + (type.getValueArray().length - 1)
                        + "] of type '" + type.getName() + "'.");
            }
        }
    }

    StorageFormEnum getStorageForm()
    {
        return storageForm;
    }

    /**
     * Returns the <var>type</var> of this enumeration array.
     */
    public HDF5EnumerationType getType()
    {
        return type;
    }

    /**
     * Returns the number of elements of this enumeration array.
     */
    public int size()
    {
        return size;
    }

    /**
     * Returns the dimensions of this enumeration array.
     */
    public int[] dimensions()
    {
        return getOrdinalValues().dimensions();
    }

    /**
     * Returns the dimensions of this enumeration array as a long.
     */
    public long[] longDimensions()
    {
        return getOrdinalValues().longDimensions();
    }

    /**
     * Returns the ordinal value for the <var>arrayIndex</var>.
     * 
     * @param arrayIndex The index in the array to get the ordinal for.
     */
    public int getOrdinal(int arrayIndex)
    {
        if (bArrayOrNull != null)
        {
            return bArrayOrNull.get(arrayIndex);
        } else if (sArrayOrNull != null)
        {
            return sArrayOrNull.get(arrayIndex);
        } else
        {
            return iArrayOrNull.get(arrayIndex);
        }
    }

    /**
     * Returns the ordinal value for the <var>arrayIndex</var>.
     * 
     * @param arrayIndexX The x index in the array to get the ordinal for.
     * @param arrayIndexY The y index in the array to get the ordinal for.
     */
    public int getOrdinal(int arrayIndexX, int arrayIndexY)
    {
        if (bArrayOrNull != null)
        {
            return bArrayOrNull.get(arrayIndexX, arrayIndexY);
        } else if (sArrayOrNull != null)
        {
            return sArrayOrNull.get(arrayIndexX, arrayIndexY);
        } else
        {
            return iArrayOrNull.get(arrayIndexX, arrayIndexY);
        }
    }

    /**
     * Returns the ordinal value for the <var>arrayIndex</var>.
     * 
     * @param arrayIndices The indices in the array to get the ordinal for.
     */
    public int getOrdinal(int... arrayIndices)
    {
        if (bArrayOrNull != null)
        {
            return bArrayOrNull.get(arrayIndices);
        } else if (sArrayOrNull != null)
        {
            return sArrayOrNull.get(arrayIndices);
        } else
        {
            return iArrayOrNull.get(arrayIndices);
        }
    }

    /**
     * Returns the string value for <var>arrayIndex</var>.
     * 
     * @param arrayIndex The index in the array to get the value for.
     */
    public String getValue(int arrayIndex)
    {
        return type.getValues().get(getOrdinal(arrayIndex));
    }

    /**
     * Returns the string value for <var>arrayIndex</var>.
     * 
     * @param arrayIndexX The x index in the array to get the value for.
     * @param arrayIndexY The y index in the array to get the value for.
     */
    public String getValue(int arrayIndexX, int arrayIndexY)
    {
        return type.getValues().get(getOrdinal(arrayIndexX, arrayIndexY));
    }

    /**
     * Returns the string value for <var>arrayIndex</var>.
     * 
     * @param arrayIndices The indices in the array to get the value for.
     */
    public String getValue(int... arrayIndices)
    {
        return type.getValues().get(getOrdinal(arrayIndices));
    }

    /**
     * Returns the string values for all elements of this array.
     */
    public MDArray<String> getValues()
    {
        final int len = size();
        final MDArray<String> values = new MDArray<String>(String.class, dimensions());
        final String[] flatValues = values.getAsFlatArray();
        for (int i = 0; i < len; ++i)
        {
            flatValues[i] = getValue(i);
        }
        return values;
    }

    /**
     * Returns the values for all elements of this array as an enum array with enums of type
     * <var>enumClass</var>.
     */
    public <T extends Enum<T>> MDArray<T> getValues(Class<T> enumClass)
    {
        final int len = size();
        final MDArray<T> values = new MDArray<T>(enumClass, dimensions());
        final T[] flatValues = values.getAsFlatArray();
        for (int i = 0; i < len; ++i)
        {
            flatValues[i] = Enum.valueOf(enumClass, getValue(i));
        }
        return values;
    }

    /**
     * Returns the ordinal values for all elements of this array.
     */
    public MDAbstractArray<?> getOrdinalValues()
    {
        switch (getStorageForm())
        {
            case BYTE:
                return bArrayOrNull;
            case SHORT:
                return sArrayOrNull;
            case INT:
                return iArrayOrNull;
        }
        throw new Error("Illegal storage form.");
    }

    byte[] toStorageForm()
    {
        switch (getStorageForm())
        {
            case BYTE:
                return bArrayOrNull.getAsFlatArray();
            case SHORT:
                return NativeData.shortToByte(sArrayOrNull.getAsFlatArray(), ByteOrder.NATIVE);
            case INT:
                return NativeData.intToByte(iArrayOrNull.getAsFlatArray(), ByteOrder.NATIVE);
        }
        throw new Error("Illegal storage form (" + getStorageForm() + ".)");
    }

    static HDF5EnumerationValueMDArray fromStorageForm(HDF5EnumerationType enumType, byte[] data,
            int offset, int[] dimensions, int len)
    {
        switch (enumType.getStorageForm())
        {
            case BYTE:
                final byte[] subArray = new byte[len];
                System.arraycopy(data, offset, subArray, 0, len);
                return new HDF5EnumerationValueMDArray(enumType, new MDByteArray(subArray,
                        dimensions));
            case SHORT:
                return new HDF5EnumerationValueMDArray(enumType, new MDShortArray(
                        HDFNativeData.byteToShort(data, offset, len), dimensions));
            case INT:
                return new HDF5EnumerationValueMDArray(enumType, new MDIntArray(
                        HDFNativeData.byteToInt(data, offset, len), dimensions));
        }
        throw new Error("Illegal storage form (" + enumType.getStorageForm() + ".)");
    }

    //
    // Iterable
    //

    public Iterator<MDArray<String>.ArrayEntry> iterator()
    {
        return getValues().iterator();
    }

    @Override
    public String toString()
    {
        return getValues().toString();
    }

}