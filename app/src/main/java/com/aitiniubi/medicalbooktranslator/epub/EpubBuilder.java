package com.aitiniubi.medicalbooktranslator.epub;

import java.io.*;import java.nio.charset.StandardCharsets;import java.util.zip.*;import java.util.*;

public final class EpubBuilder {
    private EpubBuilder() {}
    public static void copyWithReplacements(File source, File output, Map<String,String> replacements) throws Exception {
        try(ZipFile in=new ZipFile(source); ZipOutputStream out=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(output)))){
            Enumeration<? extends ZipEntry> en=in.entries();
            while(en.hasMoreElements()){
                ZipEntry old=en.nextElement(); ZipEntry ne=new ZipEntry(old.getName()); ne.setTime(old.getTime());
                String replacement=replacements.get(old.getName());
                byte[] payload = replacement!=null ? replacement.getBytes(StandardCharsets.UTF_8) : readAll(in.getInputStream(old));
                if("mimetype".equals(old.getName())) { ne.setMethod(ZipEntry.STORED); ne.setSize(payload.length); java.util.zip.CRC32 crc=new java.util.zip.CRC32(); crc.update(payload); ne.setCrc(crc.getValue()); }
                out.putNextEntry(ne);
                out.write(payload);
                out.closeEntry();
            }
        }
    }
    private static byte[] readAll(InputStream in) throws IOException { try(InputStream is=in; ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[16384];int n;while((n=is.read(b))>0)out.write(b,0,n);return out.toByteArray();} }
}
