package com.botfury;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class FriendStorageTest {
    @TempDir Path directory;
    @Test void editsReadFreshStateAndPreserveOtherSavedFriends() throws Exception {
        String before=System.getProperty("botfury.advancedModulesFile");
        System.setProperty("botfury.advancedModulesFile",directory.resolve("permission").toString());
        try {
            Path list=directory.resolve("botfury-friends.txt");
            Files.writeString(list,"Alice\n");
            BotFriends.edit("add","Bob");
            Files.writeString(list,"alice\nbob\nCarol\n");
            BotFriends.edit("remove","BOB");
            assertEquals("alice\ncarol\n",Files.readString(list));
            assertThrows(IllegalArgumentException.class,()->BotFriends.edit("add","bad/name"));
            assertEquals("alice\ncarol\n",Files.readString(list));
        } finally {
            if (before==null) System.clearProperty("botfury.advancedModulesFile");else System.setProperty("botfury.advancedModulesFile",before);
        }
    }
}
