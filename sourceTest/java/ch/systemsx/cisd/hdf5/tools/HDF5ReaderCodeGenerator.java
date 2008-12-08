/*
 * Copyright 2008 ETH Zuerich, CISD
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

package ch.systemsx.cisd.hdf5.tools;

import java.io.File;
import java.io.IOException;

import org.apache.commons.io.FileUtils;

import ch.systemsx.cisd.hdf5.HDF5Writer;

/**
 * A code generator for the identical parts of the {@link HDF5Writer} class for different numerical
 * types.
 * 
 * @author Bernd Rinn
 */
public class HDF5ReaderCodeGenerator
{

    public static void main(String[] args) throws IOException
    {
        final String template =
                FileUtils.readFileToString(new File(
                        "source/java/ch/systemsx/cisd/hdf5/HDF5Reader.java.tmpl"));
        HDF5CodeGenerator.generateCode(template);
    }

}