package dev.workbench.ops;

import dev.workbench.registry.OpException;
import java.util.*;

/** Explicit caller-selected rows; the ordinary whole listing remains available. */
final class SmaliWindow {
    record Range(int from,int to) {}
    static Range parse(String value)throws OpException {
        if(value==null)return null;
        try {
            String[] parts=value.split(",",-1);
            if(parts.length!=2)throw new IllegalArgumentException();
            int from=Integer.parseInt(parts[0].trim()),to=Integer.parseInt(parts[1].trim());
            if(from<1 || to<from)throw new IllegalArgumentException();
            return new Range(from,to);
        }catch(IllegalArgumentException error){throw OpException.syntax("smali window must be two positive 1-based row numbers, from <= to",value);}
    }
    static Map<String,Object> select(Map<String,Object> listing,Range range)throws OpException {
        if(range==null)return listing;
        if(!(listing.get("rows") instanceof List<?> rows))throw OpException.unsupported("This engine does not expose smali rows for windowed reads","");
        int total=rows.size(),start=Math.min(range.from()-1,total),end=Math.min(range.to(),total);
        Map<String,Object> result=new LinkedHashMap<>(listing);
        result.put("rows",new ArrayList<>(rows.subList(start,end)));result.put("count",end-start);
        result.put("total",total);
        Map<String,Object> window=new LinkedHashMap<>();window.put("from",range.from());window.put("to",end);window.put("totalRows",total);
        window.put("more",end<total);
        if(end<total)window.put("next",(end+1)+","+Math.min(total,(long)end+range.to()-range.from()+1));
        result.put("window",window);return result;
    }
}
