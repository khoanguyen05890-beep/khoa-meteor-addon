package com.khoa.automationbot;

import com.khoa.automationbot.modules.KhoaAutoMiner;
import com.khoa.automationbot.modules.KhoaAutoSandBot;
import com.khoa.automationbot.modules.KhoaItemCollector;
import com.khoa.automationbot.modules.KhoaLitematicaBuilder;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KhoaAddon extends MeteorAddon {
    public static final Logger LOG = LoggerFactory.getLogger("khoamoxe");
    public static final Category CATEGORY = new Category("Khoa");

    @Override
    public void onInitialize() {
        LOG.info("KHOAMOXE (Meteor addon) loaded");
        Modules.get().add(new KhoaAutoMiner());
        Modules.get().add(new KhoaAutoSandBot());
        Modules.get().add(new KhoaItemCollector());
        Modules.get().add(new KhoaLitematicaBuilder());
    }

    @Override
    public void onRegisterCategories() {
        Modules.get().registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.khoa.automationbot";
    }
}
