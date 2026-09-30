package com.aitiniubi.medicalbooktranslator.translation;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class GlossaryManager {
    public static final class Term {
        public final String english, vietnamese, category, note;
        Term(String e,String v,String c,String n){english=e;vietnamese=v;category=c;note=n;}
    }
    private GlossaryManager(){}

    public static File file(File workspace){ return new File(workspace,"medical-glossary.csv"); }

    public static List<Term> load(File workspace){
        List<Term> out=new ArrayList<>(); File f=file(workspace);
        if(!f.isFile())return out;
        try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(f),StandardCharsets.UTF_8))){
            String line; boolean first=true;
            while((line=r.readLine())!=null){
                if(first){first=false;if(line.startsWith("\uFEFF"))line=line.substring(1);}
                if(line.trim().isEmpty())continue;
                List<String> p=parseCsv(line); if(p.size()<2)continue;
                String e=p.get(0).trim(),v=p.get(1).trim();
                if(e.isEmpty()||v.isEmpty()||e.equalsIgnoreCase("English"))continue;
                out.add(new Term(e,v,p.size()>2?p.get(2).trim():"",p.size()>3?p.get(3).trim():""));
            }
        }catch(Exception ignored){}
        return out;
    }

    public static int importCsv(File workspace,InputStream input)throws IOException{
        if(!workspace.exists())workspace.mkdirs();
        File target=file(workspace),tmp=new File(workspace,"medical-glossary.tmp");
        try(BufferedReader r=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8));
            BufferedWriter w=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp),StandardCharsets.UTF_8))){
            String line;boolean first=true;int count=0;
            while((line=r.readLine())!=null){
                if(first){first=false;if(line.startsWith("\uFEFF"))line=line.substring(1);}
                if(line.trim().isEmpty())continue;
                List<String> p=parseCsv(line);if(p.size()<2)continue;
                String e=p.get(0).trim(),v=p.get(1).trim();
                if(e.isEmpty()||v.isEmpty()||e.equalsIgnoreCase("English"))continue;
                w.write(csv(e)+","+csv(v)+","+csv(p.size()>2?p.get(2).trim():"")+","+csv(p.size()>3?p.get(3).trim():""));w.newLine();count++;
            }
        }
        if(target.exists())target.delete();
        if(!tmp.renameTo(target))throw new IOException("Không thể lưu glossary.");
        return count;
    }

    public static String promptTerms(String text,List<Term> terms){
        if(text==null||terms==null||terms.isEmpty())return "";
        String low=text.toLowerCase(Locale.US);StringBuilder b=new StringBuilder();int n=0;
        for(Term t:terms){
            String needle=t.english.toLowerCase(Locale.US).trim();
            if(needle.length()<2||!low.contains(needle))continue;
            b.append("- ").append(t.english).append(" → ").append(t.vietnamese);
            if(!t.note.isEmpty())b.append(" (").append(t.note).append(")");
            b.append("\n");if(++n>=40)break;
        }
        return n==0?"":"APPROVED MEDICAL TERMINOLOGY. Use these Vietnamese translations exactly; do not replace them with synonyms:\n"+b;
    }

    private static String csv(String s){return "\""+(s==null?"":s.replace("\"","\"\""))+"\"";}
    private static List<String> parseCsv(String s){
        List<String> out=new ArrayList<>();StringBuilder b=new StringBuilder();boolean q=false;
        for(int i=0;i<s.length();i++){char c=s.charAt(i);
            if(c=='\"'){if(q&&i+1<s.length()&&s.charAt(i+1)=='\"'){b.append('\"');i++;}else q=!q;}
            else if(c==','&&!q){out.add(b.toString());b.setLength(0);}else b.append(c);
        }
        out.add(b.toString());return out;
    }
}