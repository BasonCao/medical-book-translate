package com.aitiniubi.medicalbooktranslator.epub;

import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

public final class EpubAnalyzer {
    private EpubAnalyzer() {}
    public static EpubBook analyze(File file) throws Exception {
        if (!file.isFile() || !file.getName().toLowerCase(Locale.US).endsWith(".epub")) throw new IOException("Không phải file EPUB");
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry mime = zip.getEntry("mimetype");
            if (mime == null) throw new IOException("EPUB thiếu mimetype");
            String mt = read(zip, mime).trim();
            if (!"application/epub+zip".equals(mt)) throw new IOException("mimetype EPUB không hợp lệ");
            String opfPath = findOpf(zip);
            EpubBook book = new EpubBook(file.getAbsolutePath()); book.totalFiles = zip.size();
            String opf = read(zip, zip.getEntry(opfPath));
            parseManifest(zip, opfPath, opf, book);
            parseContent(zip, book);
            return book;
        }
    }
    private static String findOpf(ZipFile z) throws Exception {
        String c = read(z, z.getEntry("META-INF/container.xml"));
        Document d = parse(c.getBytes("UTF-8")); NodeList n=d.getElementsByTagNameNS("*","rootfile");
        if(n.getLength()==0) throw new IOException("Không tìm thấy OPF");
        return ((Element)n.item(0)).getAttribute("full-path");
    }
    private static void parseManifest(ZipFile z,String opfPath,String opf,EpubBook b) throws Exception {
        Document d=parse(opf.getBytes("UTF-8"));
        NodeList title=d.getElementsByTagNameNS("*","title"); if(title.getLength()>0) b.title=title.item(0).getTextContent().trim();
        NodeList items=d.getElementsByTagNameNS("*","item"); String base=opfPath.contains("/")?opfPath.substring(0,opfPath.lastIndexOf('/')+1):"";
        for(int i=0;i<items.getLength();i++){ Element e=(Element)items.item(i); String href=e.getAttribute("href"); String media=e.getAttribute("media-type"); String p=base+href;
            if("application/xhtml+xml".equals(media)) b.xhtmlFiles.add(normalize(p));
            else if(media.startsWith("image/")) b.imageFiles.add(normalize(p)); else if("text/css".equals(media)) b.cssFiles.add(normalize(p)); }
    }
    private static void parseContent(ZipFile z,EpubBook b) throws Exception {
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true); f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        for(String p:b.xhtmlFiles){ ZipEntry e=z.getEntry(p); if(e==null) continue; Document d=parse(read(z,e).getBytes("UTF-8"));
            b.paragraphCount += d.getElementsByTagName("p").getLength(); b.figureCount += d.getElementsByTagName("figure").getLength(); b.tableCount += d.getElementsByTagName("table").getLength(); b.imageReferenceCount += d.getElementsByTagName("img").getLength(); }
    }
    static Document parse(byte[] bytes) throws Exception { DocumentBuilderFactory f=DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true); f.setFeature("http://xml.org/sax/features/external-general-entities",false); f.setFeature("http://xml.org/sax/features/external-parameter-entities",false); return f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes)); }
    static String read(ZipFile z,ZipEntry e) throws Exception { if(e==null) throw new FileNotFoundException("EPUB entry missing"); try(InputStream in=z.getInputStream(e); ByteArrayOutputStream out=new ByteArrayOutputStream()){ byte[] buf=new byte[8192]; int n; while((n=in.read(buf))>0) out.write(buf,0,n); return new String(out.toByteArray(),"UTF-8"); } }
    static String normalize(String p){ String[] a=p.split("/"); ArrayDeque<String>s=new ArrayDeque<>(); for(String x:a){ if(x.isEmpty()||".".equals(x))continue; if("..".equals(x)){if(!s.isEmpty())s.removeLast();}else s.add(x);} return String.join("/",s); }
}
