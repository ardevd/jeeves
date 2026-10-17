package dev.workbench.it;

import com.pnfsoftware.jeb.core.RuntimeProjectUtil;
import com.pnfsoftware.jeb.core.output.IActionableItem;
import com.pnfsoftware.jeb.core.properties.IPropertyManager;
import com.pnfsoftware.jeb.core.units.code.android.*;
import com.pnfsoftware.jeb.core.units.code.java.*;
import com.pnfsoftware.jeb.core.util.DecompilerHelper;
import dev.workbench.engine.jeb.*;
import java.nio.file.*;
import java.util.*;

/** Variable reads retain native identities and positions without changing normal call navigation. */
public final class VariableRenderingIT {
    private static final String CALLER="Lfixture/RenameFamily;->call(Lfixture/RenameFamily$Reader;I)I";
    private static final String CALLEE="Lfixture/RenameFamily$Reader;->a(I)I";
    private static final List<String> KEYS=List.of(JavaDocument.propnameResolveMethodCallTargets,
            JavaDocument.propnameResolveFieldAccessTargets);
    private static int checks;
    private static void check(boolean value,String why) {
        if(!value)throw new AssertionError(why);checks++;System.out.println("PASS "+why);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    private static void ready(JebEngine engine)throws Exception {
        for(;;){var info=engine.info();if("ready".equals(info.get("state")))return;
            if("error".equals(info.get("state")))throw new AssertionError(info);Thread.sleep(100);}
    }
    private record Occurrence(String id,String text,int line) {}
    private static void inspect(JebEngine engine)throws Exception {
        int methods=0,variables=0;
        for(var project:JebCore.engines().getProjects())for(var dex:RuntimeProjectUtil.findUnits(project,IDexUnit.class)) {
            for(String suffix:List.of("","$Base","$Child","$Peer","$Reader","$Unrelated")) {
                var cls=dex.getClass("Lfixture/RenameFamily"+suffix+";");
                if(cls==null||!cls.isInternal())continue;
                var source=(IJavaSourceUnit)((IDexDecompilerUnit)DecompilerHelper.getDecompiler(dex)).decompileToUnit(cls.getAddress());
                var pm=source.getPropertyManager();
                var saved=new LinkedHashMap<String,Object>();
                for(String key:KEYS)saved.put(key,pm.getValue(key,IPropertyManager.DEPTH_FULL,false,false));
                try {
                    for(var entry:Map.<String,Object>of("DisplayMethodInternalsAsComment",0,"GenerateSpecialMetaComments",0,
                            "SplitCallArgThreshold",1_000_000,"InsertBlankLinesAfterCompounds",false,
                            "UseVarReservedType",false,JavaDocument.propnameDisregardCollapse,true).entrySet()) {
                        saved.put(entry.getKey(),pm.getValue(entry.getKey(),IPropertyManager.DEPTH_FULL,false,false));
                        if(entry.getValue() instanceof Integer n)pm.setInteger(entry.getKey(),n);else pm.setBoolean(entry.getKey(),(Boolean)entry.getValue());
                    }
                    // Exercise explicitly enabled, disabled and inherited/default renderer properties.
                    for(Boolean setting:Arrays.asList(Boolean.TRUE,Boolean.FALSE,null)) {
                        for(String key:KEYS)pm.setBoolean(key,setting);
                        var expectedSettings=new LinkedHashMap<String,Object>();
                        var expectedValues=new LinkedHashMap<String,Boolean>();
                        for(String key:KEYS)expectedSettings.put(key,pm.getValue(key,IPropertyManager.DEPTH_FULL,false,false));
                        for(String key:KEYS)expectedValues.put(key,pm.getBooleanUnsafe(key));
                        var before=engine.decompile(cls.getAddress(),Map.of("scope","class","tokens",true));
                        var document=new JavaDocument(source);
                        Set<Occurrence> reference=new HashSet<>();
                        try {
                            // Independent full-format reference, including the last line: no one-line request.
                            String complete=document.format();
                            var part=document.getDocumentPart(document.getFirstAnchor(),(int)complete.lines().count()+5,0);
                            check(part.getCountOfLines()>1,"reference contains a complete native class");
                            int line=0;
                            for(var row:part.getLines()) {
                                line++;String text=String.valueOf(row.getText());
                                for(var item:row.getItems())if(item instanceof IActionableItem action) {
                                    int a=item.getOffset(),b=a+item.getLength();
                                    if(a>=0&&b<=text.length())reference.add(new Occurrence(Long.toUnsignedString(action.getItemId()),text.substring(a,b),line));
                                }
                            }
                        } finally {document.dispose();}
                        for(var method:cls.getMethods()) {
                            var rows=engine.locals(method.getSignature(false));
                            methods++;variables+=rows.size();
                            for(Object value:rows) {
                                var row=map(value);
                                check(reference.contains(new Occurrence(String.valueOf(row.get("itemId")),
                                        String.valueOf(row.get("name")),((Number)row.get("line")).intValue())),
                                        "inventory ID, displayed name and line match full rendering: "+method.getSignature(false)
                                                +" "+row+" reference="+reference.stream().filter(r->r.id().equals(row.get("itemId"))).toList());
                            }
                            engine.methodParameters(method.getSignature(false));
                            for(String key:KEYS)check(Objects.equals(expectedSettings.get(key),pm.getValue(key,IPropertyManager.DEPTH_FULL,false,false)),
                                    "variable and argument reads restore the exact renderer override: "+key+"="+setting
                                            +" (before="+expectedSettings.get(key)+", after="+pm.getValue(key,IPropertyManager.DEPTH_FULL,false,false)+")");
                            for(String key:KEYS)check(Objects.equals(expectedValues.get(key),pm.getBooleanUnsafe(key)),
                                    "variable reads preserve the effective renderer value: "+key+"="+setting
                                            +" (before="+expectedValues.get(key)+", after="+pm.getBooleanUnsafe(key)+")");
                        }
                        var after=engine.decompile(cls.getAddress(),Map.of("scope","class","tokens",true));
                        check(before.get("text").equals(after.get("text")),"variable reads preserve complete displayed code");
                        check(before.get("tokens").equals(after.get("tokens")),"variable reads preserve resolved semantic navigation targets");
                    }
                } finally {
                    for(var entry:saved.entrySet()) {
                        if(Set.of("DisplayMethodInternalsAsComment","GenerateSpecialMetaComments","SplitCallArgThreshold").contains(entry.getKey()))
                            pm.setInteger(entry.getKey(),(Integer)entry.getValue());
                        else pm.setBoolean(entry.getKey(),(Boolean)entry.getValue());
                    }
                }
            }
        }
        check(methods>20&&variables>20,"all fixture classes and complete variable inventories were exercised");
    }
    public static void main(String[] args)throws Exception {
        Path output=Path.of(args[1]),input=output.resolve("VariableRendering.dex");Files.copy(Path.of(args[0]),input);
        var engine=new JebEngine("variable-rendering","fixture",input.toString(),input.toFile(),output.resolve("VariableRendering.jdb2").toFile());
        try {
            engine.openAsync();ready(engine);inspect(engine);
            engine.rename(CALLEE,"readValue","human");
            var argument=engine.locals(CALLER).stream().map(VariableRenderingIT::map)
                    .filter(row->Integer.valueOf(1).equals(row.get("parameterIndex"))).findFirst().orElseThrow();
            String identity=String.valueOf(argument.get("originalSymbol"));
            engine.renameLocal(CALLER,String.valueOf(argument.get("name")),"requestedValue","llm:fixture");
            check(engine.locals(CALLER).stream().map(VariableRenderingIT::map)
                    .anyMatch(row->identity.equals(row.get("originalSymbol"))&&"requestedValue".equals(row.get("name"))),
                    "native argument identity remains stable and its new name is immediately visible");
            inspect(engine);
            check(String.valueOf(engine.decompile(CALLER,Map.of()).get("text")).contains("readValue"),
                    "normal rendering still resolves the newly named interface callee");
            System.out.println("VariableRenderingIT: "+checks+" checks passed");
        }catch(Exception|AssertionError failure){failure.printStackTrace(System.out);throw failure;}
        finally{engine.discardUnsaved();engine.close();JebCore.shutdown();}
    }
}
