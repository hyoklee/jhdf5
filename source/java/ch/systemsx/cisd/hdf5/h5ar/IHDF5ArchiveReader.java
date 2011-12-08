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

package ch.systemsx.cisd.hdf5.h5ar;

import java.io.File;
import java.io.OutputStream;
import java.util.List;

import ch.systemsx.cisd.base.exceptions.IOExceptionUnchecked;

/**
 * An interface for an HDF5 archive reader.
 *
 * @author Bernd Rinn
 */
public interface IHDF5ArchiveReader
{
    public void close();

    public List<ArchiveEntry> list(String fileOrDir);

    public List<ArchiveEntry> list(String fileOrDir, ListParameters params);

    public IHDF5ArchiveReader list(String fileOrDir, IListEntryVisitor visitor);

    public IHDF5ArchiveReader list(String fileOrDir, IListEntryVisitor visitor, ListParameters params);

    public IHDF5ArchiveReader verifyAgainstFilesystem(String fileOrDir, String rootDirectory,
            IListEntryVisitor visitor);

    public IHDF5ArchiveReader verifyAgainstFilesystem(String fileOrDir, String rootDirectory,
            IListEntryVisitor visitor, VerifyParameters params);

    public IHDF5ArchiveReader extract(String path, OutputStream out) throws IOExceptionUnchecked;

    public byte[] extract(String path) throws IOExceptionUnchecked;

    public IHDF5ArchiveReader extractToFilesystem(File root, String path) throws IllegalStateException;

    public IHDF5ArchiveReader extractToFilesystem(File root, String path, IListEntryVisitor visitorOrNull)
            throws IllegalStateException;

    public IHDF5ArchiveReader extractToFilesystem(File root, String path, ArchivingStrategy strategy,
            IListEntryVisitor visitorOrNull) throws IllegalStateException;

}