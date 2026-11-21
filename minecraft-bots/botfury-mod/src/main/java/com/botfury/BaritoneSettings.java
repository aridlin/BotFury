package com.botfury;
final class BaritoneSettings implements AutoCloseable {
    private final Object breaking,placing,oldBreak,oldPlace;
    private final java.lang.reflect.Field value;
    BaritoneSettings()throws ReflectiveOperationException{
        Object settings=Class.forName("baritone.api.BaritoneAPI").getMethod("getSettings").invoke(null);
        breaking=settings.getClass().getField("allowBreak").get(settings);placing=settings.getClass().getField("allowPlace").get(settings);
        value=breaking.getClass().getField("value");oldBreak=value.get(breaking);oldPlace=value.get(placing);
        try{value.set(breaking,true);value.set(placing,false);}catch(ReflectiveOperationException e){close();throw e;}
    }
    @Override public void close(){try{value.set(breaking,oldBreak);value.set(placing,oldPlace);}catch(ReflectiveOperationException e){BotEvents.add("warn","mining","Could not restore Baritone settings");}}
}
