package com.iflytek.skillhub.service.archive;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Checks ZIP central-directory metadata that java.util.zip does not expose. */
public final class ZipArchiveSafety {
    private static final int CENTRAL_HEADER_SIZE = 46;
    private static final int EOCD_MIN_SIZE = 22;
    private static final int EOCD_MAX_SEARCH = 65_557;
    private static final int UNIX_FILE_TYPE_MASK = 0170000;
    private static final int UNIX_SYMBOLIC_LINK = 0120000;

    private ZipArchiveSafety() {}

    public static void rejectSymbolicLinks(Path archivePath) throws IOException {
        rejectSymbolicLinks(archivePath, Integer.MAX_VALUE, Long.MAX_VALUE);
    }

    public static void rejectSymbolicLinks(Path archivePath, int maxEntries, long maxCentralBytes)
            throws IOException {
        try (FileChannel channel = FileChannel.open(archivePath, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            int tailLength = (int) Math.min(fileSize, EOCD_MAX_SEARCH);
            ByteBuffer tail = ByteBuffer.allocate(tailLength).order(ByteOrder.LITTLE_ENDIAN);
            readFully(channel, tail, fileSize - tailLength);
            int eocd = findEndOfCentralDirectory(tail);
            if (eocd < 0 || eocd + EOCD_MIN_SIZE > tailLength) {
                throw invalid("Invalid ZIP end-of-central-directory record");
            }
            int entryCount = Short.toUnsignedInt(tail.getShort(eocd + 10));
            long centralSize = Integer.toUnsignedLong(tail.getInt(eocd + 12));
            long centralOffset = Integer.toUnsignedLong(tail.getInt(eocd + 16));
            if (entryCount == 0xffff || centralSize == 0xffffffffL || centralOffset == 0xffffffffL) {
                throw invalid("ZIP64 archives are not supported");
            }
            if (centralOffset + centralSize > fileSize) {
                throw invalid("Invalid ZIP central-directory bounds");
            }
            if (entryCount > maxEntries || centralSize > maxCentralBytes) {
                throw invalid("ZIP central directory exceeds package limits");
            }

            long cursor = centralOffset;
            for (int index = 0; index < entryCount; index++) {
                ByteBuffer header = ByteBuffer.allocate(CENTRAL_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
                readFully(channel, header, cursor);
                if (header.getInt(0) != 0x02014b50) {
                    throw invalid("Invalid ZIP central-directory entry");
                }
                int creatorSystem = Byte.toUnsignedInt(header.get(5));
                int nameLength = Short.toUnsignedInt(header.getShort(28));
                int extraLength = Short.toUnsignedInt(header.getShort(30));
                int commentLength = Short.toUnsignedInt(header.getShort(32));
                long externalAttributes = Integer.toUnsignedLong(header.getInt(38));
                int unixMode = (int) (externalAttributes >>> 16);
                if (creatorSystem == 3 && (unixMode & UNIX_FILE_TYPE_MASK) == UNIX_SYMBOLIC_LINK) {
                    throw invalid("ZIP archive must not contain symbolic links");
                }
                cursor += CENTRAL_HEADER_SIZE + nameLength + extraLength + commentLength;
                if (cursor > centralOffset + centralSize) {
                    throw invalid("Invalid ZIP central-directory entry bounds");
                }
            }
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position + buffer.position());
            if (read < 0) {
                throw invalid("Unexpected end of ZIP archive");
            }
        }
    }

    private static int findEndOfCentralDirectory(ByteBuffer tail) {
        byte[] bytes = tail.array();
        int signature = 0x06054b50;
        for (int index = bytes.length - EOCD_MIN_SIZE; index >= 0; index--) {
            if ((bytes[index] & 0xff) == (signature & 0xff)
                    && (bytes[index + 1] & 0xff) == ((signature >>> 8) & 0xff)
                    && (bytes[index + 2] & 0xff) == ((signature >>> 16) & 0xff)
                    && (bytes[index + 3] & 0xff) == ((signature >>> 24) & 0xff)) {
                int commentLength = Short.toUnsignedInt(tail.getShort(index + 20));
                if (index + EOCD_MIN_SIZE + commentLength == bytes.length) {
                    return index;
                }
            }
        }
        return -1;
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException(detail);
    }
}
