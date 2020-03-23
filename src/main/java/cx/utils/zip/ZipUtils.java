/*
 * Copyright (c) Celtx Inc. <https://www.celtx.com>
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

package cx.utils.zip;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.Collections;

public class ZipUtils {

    /*
     * adapted from https://fahdshariff.blogspot.com/2011/08/java-7-working-with-zip-files.html
     */
    public static void createZip(String zipFilename, String[] filenames)
            throws IOException {

        try (FileSystem zipFileSystem = createZipFileSystem(zipFilename, true)) {
            final Path root = zipFileSystem.getPath("/");

            //iterate over the files we need to add
            for (String filename : filenames) {
                final Path src = Paths.get(filename);

                if (!Files.isDirectory(src)) {
                    final Path dest = zipFileSystem.getPath(root.toString(),
                            src.toString());
                    final Path parent = dest.getParent();
                    if (Files.notExists(parent)) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult visitFile(Path file,
                                                         BasicFileAttributes attrs) throws IOException {
                            final Path dest = zipFileSystem.getPath(root.toString(),
                                    file.toString());
                            Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult preVisitDirectory(Path dir,
                                                                 BasicFileAttributes attrs) throws IOException {
                            final Path dirToCreate = zipFileSystem.getPath(root.toString(),
                                    dir.toString());
                            if (Files.notExists(dirToCreate)) {
                                Files.createDirectories(dirToCreate);
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
            }
        }
    }

    private static FileSystem createZipFileSystem(String zipFilename, boolean create) throws IOException {
        final Path path = Paths.get(zipFilename);
        final URI uri = URI.create("jar:file:" + path.toUri().getPath());
        return FileSystems.newFileSystem(uri, create ? Collections.singletonMap("create", "true") : Collections.emptyMap());
    }

    public static String generateFileName(String prefix, String suffix, File dir) {
        return TempDirectory.generateFileName(prefix, suffix, dir);
    }

    // adapted from https://github.com/openjdk/jdk/blob/jdk8-b120/jdk/src/share/classes/java/io/File.java#L1890
    private static class TempDirectory {
        private TempDirectory() {
        }

        private static final File tmpdir = new File(System.getProperty("java.io.tmpdir"));
        // file name generation
        private static final SecureRandom random = new SecureRandom();

        static String generateFileName(String prefix, String suffix, File dir) {
            long n = random.nextLong();
            if (n == Long.MIN_VALUE) {
                n = 0;      // corner case
            } else {
                n = Math.abs(n);
            }

            // Use only the file name from the supplied prefix
            if (null == dir) dir = tmpdir;
            prefix = (new File(dir, prefix)).getAbsolutePath();

            return prefix + n + suffix;
        }
    }
}
