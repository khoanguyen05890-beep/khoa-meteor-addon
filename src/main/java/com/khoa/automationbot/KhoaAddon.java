package com.khoa.automationbot;

import com.khoa.automationbot.modules.KhoaAutoMiner;
import com.khoa.automationbot.modules.KhoaItemCollector;
import com.khoa.automationbot.modules.KhoaLitematicaBuilder;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KhoaAddon extends MeteorAddon {
    public static final Logger LOG = LoggerFactory.getLogger("khoa-automation-bot");
    public static final Category CATEGORY = new Category("Khoa");

    @Override
    public void onInitialize() {
        LOG.info("KHOA AUTOMATION BOT (Meteor addon) loaded");
        Modules.get().add(new KhoaAutoMiner());
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
