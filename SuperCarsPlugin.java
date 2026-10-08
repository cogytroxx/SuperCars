package com.supercars;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Horse;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SuperCars - drivable supercars for Paper 26.2.
 * Every car is an invisible saddled horse (gives WASD driving, steering and speed)
 * with item displays (body + cabin) following it.
 */
public class SuperCarsPlugin extends JavaPlugin implements Listener, TabCompleter {

    private static final int PAGE_SIZE = 45;

    /** One car model from config.yml */
    private record CarType(String id, String brand, String name, double speed, Material material) {}

    /** A car that is currently placed in the world. */
    private static final class ActiveCar {
        final CarType type;
        final Horse horse;
        final ItemDisplay body;
        final ItemDisplay cabin;
        final UUID owner;

        ActiveCar(CarType type, Horse horse, ItemDisplay body, ItemDisplay cabin, UUID owner) {
            this.type = type;
            this.horse = horse;
            this.body = body;
            this.cabin = cabin;
            this.owner = owner;
        }
    }

    /** Holder so we can recognise our own garage inventory. */
    private static final class GarageHolder implements InventoryHolder {
        final int page;
        Inventory inventory;

        GarageHolder(int page) {
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final Map<String, CarType> carTypes = new LinkedHashMap<>();
    private final Map<UUID, ActiveCar> activeCars = new HashMap<>(); // key = horse uuid
    private NamespacedKey carKey;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        carKey = new NamespacedKey(this, "car_id");
        loadCars();
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("supercar");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        startTickTask();
        getLogger().info("Loaded " + carTypes.size() + " cars.");
    }

    @Override
    public void onDisable() {
        for (ActiveCar car : new ArrayList<>(activeCars.values())) {
            removeCar(car);
        }
        activeCars.clear();
    }

    // ------------------------------------------------------------------ config

    private void loadCars() {
        carTypes.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("cars");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection c = sec.getConfigurationSection(id);
            if (c == null) continue;
            Material mat = Material.matchMaterial(c.getString("material", "GRAY_CONCRETE"));
            if (mat == null || !mat.isItem()) mat = Material.GRAY_CONCRETE;
            carTypes.put(id.toLowerCase(Locale.ROOT), new CarType(
                    id.toLowerCase(Locale.ROOT),
                    c.getString("brand", "Other"),
                    c.getString("name", id),
                    c.getDouble("speed", 0.6),
                    mat));
        }
    }

    private Component msg(String key) {
        return msg(key, Map.of());
    }

    private Component msg(String key, Map<String, String> replace) {
        String prefix = getConfig().getString("messages.prefix", "");
        String text = getConfig().getString("messages." + key, key);
        for (var e : replace.entrySet()) text = text.replace(e.getKey(), e.getValue());
        return LegacyComponentSerializer.legacyAmpersand().deserialize(prefix + text);
    }

    // ------------------------------------------------------------------ items

    private ItemStack createCarItem(CarType type) {
        ItemStack item = new ItemStack(Material.CARROT_ON_A_STICK);
        ItemMeta meta = item.getItemMeta();
        MiniMessage mm = MiniMessage.miniMessage();
        meta.displayName(mm.deserialize("<!italic><gold><bold>" + type.name()));
        meta.lore(List.of(
                mm.deserialize("<!italic><dark_gray>" + type.brand()),
                mm.deserialize("<!italic><gray>Right click a block to place the car"),
                mm.deserialize("<!italic><gray>Speed: <yellow>" + (int) Math.round(type.speed() * 100) + "</yellow> / 100")));
        if (getConfig().getBoolean("settings.use-resource-pack-models", false)) {
            NamespacedKey model = NamespacedKey.fromString("supercars:" + type.id());
            if (model != null) meta.setItemModel(model);
        }
        meta.getPersistentDataContainer().set(carKey, PersistentDataType.STRING, type.id());
        item.setItemMeta(meta);
        return item;
    }

    private CarType carTypeOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(carKey, PersistentDataType.STRING);
        return id == null ? null : carTypes.get(id);
    }

    private void giveItem(Player p, ItemStack item) {
        p.getInventory().addItem(item).values()
                .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
    }

    // ------------------------------------------------------------------ spawning

    private ActiveCar spawnCar(CarType type, Location loc, Player owner) {
        Horse horse = loc.getWorld().spawn(loc, Horse.class, h -> {
            h.setAdult();
            h.setTamed(true);
            h.setOwner(owner);
            h.setInvisible(true);
            h.setSilent(true);
            h.setPersistent(false);
            h.setRemoveWhenFarAway(false);
            h.setAI(false); // switched on only while somebody drives
            h.getInventory().setSaddle(new ItemStack(Material.SADDLE));
            setAttr(h, Attribute.MOVEMENT_SPEED, type.speed());
            setAttr(h, Attribute.JUMP_STRENGTH, 0.0);
            setAttr(h, Attribute.MAX_HEALTH, 100.0);
            h.setHealth(100.0);
        });

        ItemDisplay body = spawnDisplay(loc, new ItemStack(type.material()),
                new Vector3f(0f, 0.55f, 0f), new Vector3f(1.25f, 0.6f, 2.5f));
        ItemDisplay cabin = spawnDisplay(loc, new ItemStack(Material.BLACK_STAINED_GLASS),
                new Vector3f(0f, 1.0f, -0.1f), new Vector3f(1.0f, 0.5f, 1.2f));

        ActiveCar car = new ActiveCar(type, horse, body, cabin, owner.getUniqueId());
        activeCars.put(horse.getUniqueId(), car);
        return car;
    }

    private ItemDisplay spawnDisplay(Location loc, ItemStack stack, Vector3f translation, Vector3f scale) {
        return loc.getWorld().spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(stack);
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.FIXED);
            d.setTeleportDuration(1);
            d.setTransformation(new Transformation(translation, new AxisAngle4f(), scale, new AxisAngle4f()));
        });
    }

    private void setAttr(Horse h, Attribute attribute, double value) {
        AttributeInstance inst = h.getAttribute(attribute);
        if (inst != null) inst.setBaseValue(value);
    }

    private void removeCar(ActiveCar car) {
        activeCars.remove(car.horse.getUniqueId());
        for (Entity e : new ArrayList<>(car.horse.getPassengers())) {
            car.horse.removePassenger(e);
        }
        car.body.remove();
        car.cabin.remove();
        car.horse.remove();
    }

    private long carsOwnedBy(UUID uuid) {
        return activeCars.values().stream().filter(c -> c.owner.equals(uuid)).count();
    }

    private boolean isDriven(ActiveCar car) {
        return car.horse.getPassengers().stream().anyMatch(x -> x instanceof Player);
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlace(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        CarType type = carTypeOf(e.getItem());
        if (type == null) return;
        e.setCancelled(true);

        Player p = e.getPlayer();
        if (!p.hasPermission("supercars.use")) {
            p.sendMessage(msg("no-permission"));
            return;
        }
        if (!p.hasPermission("supercars.admin")
                && carsOwnedBy(p.getUniqueId()) >= getConfig().getInt("settings.max-cars-per-player", 3)) {
            p.sendMessage(msg("limit"));
            return;
        }
        Block b = e.getClickedBlock();
        if (b == null) return;
        BlockFace face = e.getBlockFace();
        Location loc = b.getRelative(face).getLocation().add(0.5, 0.0, 0.5);
        loc.setYaw(p.getLocation().getYaw());

        spawnCar(type, loc, p);
        if (p.getGameMode() != GameMode.CREATIVE) {
            e.getItem().setAmount(e.getItem().getAmount() - 1);
        }
        p.sendMessage(msg("placed"));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClickCar(PlayerInteractEntityEvent e) {
        ActiveCar car = activeCars.get(e.getRightClicked().getUniqueId());
        if (car == null) return;
        e.setCancelled(true); // never open the horse inventory
        if (e.getHand() != EquipmentSlot.HAND) return;

        Player p = e.getPlayer();
        if (p.isSneaking() && getConfig().getBoolean("settings.allow-pickup", true)) {
            if (!car.owner.equals(p.getUniqueId()) && !p.hasPermission("supercars.admin")) {
                p.sendMessage(msg("not-owner"));
                return;
            }
            removeCar(car);
            giveItem(p, createCarItem(car.type));
            p.sendMessage(msg("picked-up"));
            return;
        }
        if (!p.hasPermission("supercars.use")) {
            p.sendMessage(msg("no-permission"));
            return;
        }
        if (!isDriven(car)) {
            car.horse.setAI(true);
            car.horse.addPassenger(p);
        }
    }

    @EventHandler
    public void onHorseInventory(InventoryOpenEvent e) {
        if (e.getInventory().getHolder() instanceof Horse h && activeCars.containsKey(h.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (activeCars.containsKey(e.getEntity().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (e.getPlayer().getVehicle() instanceof Horse h && activeCars.containsKey(h.getUniqueId())) {
            h.removePassenger(e.getPlayer());
        }
    }

    // ------------------------------------------------------------------ garage menu

    private void openGarage(Player p, int page) {
        List<CarType> all = new ArrayList<>(carTypes.values());
        int pages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, pages - 1));

        GarageHolder holder = new GarageHolder(page);
        Inventory inv = Bukkit.createInventory(holder, 54,
                Component.text("SuperCars Garage  " + (page + 1) + "/" + pages));
        holder.inventory = inv;

        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < all.size(); i++) {
            inv.setItem(i, createCarItem(all.get(from + i)));
        }
        inv.setItem(45, navItem("<yellow>Previous page"));
        inv.setItem(49, navItem("<red>Close"));
        inv.setItem(53, navItem("<yellow>Next page"));
        p.openInventory(inv);
    }

    private ItemStack navItem(String miniMessageName) {
        ItemStack it = new ItemStack(Material.ARROW);
        ItemMeta m = it.getItemMeta();
        m.displayName(MiniMessage.miniMessage().deserialize("<!italic>" + miniMessageName));
        it.setItemMeta(m);
        return it;
    }

    @EventHandler
    public void onGarageClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof GarageHolder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getInventory()) return;
        if (!p.hasPermission("supercars.admin")) return;

        int slot = e.getSlot();
        if (slot == 45) {
            openGarage(p, holder.page - 1);
        } else if (slot == 53) {
            openGarage(p, holder.page + 1);
        } else if (slot == 49) {
            p.closeInventory();
        } else if (slot >= 0 && slot < PAGE_SIZE) {
            CarType type = carTypeOf(e.getCurrentItem());
            if (type != null) {
                giveItem(p, createCarItem(type));
            }
        }
    }

    @EventHandler
    public void onGarageDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof GarageHolder) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ tick task

    private void startTickTask() {
        new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                tick++;
                boolean particles = getConfig().getBoolean("settings.exhaust-particles", true);
                for (ActiveCar car : new ArrayList<>(activeCars.values())) {
                    if (!car.horse.isValid()) {
                        activeCars.remove(car.horse.getUniqueId());
                        car.body.remove();
                        car.cabin.remove();
                        continue;
                    }
                    boolean driven = isDriven(car);
                    // AI only while driven, so an empty car never wanders off
                    if (car.horse.hasAI() != driven) {
                        car.horse.setAI(driven);
                    }
                    if (!driven) {
                        car.horse.setVelocity(car.horse.getVelocity().setX(0).setZ(0));
                    }

                    Location hl = car.horse.getLocation();
                    car.body.teleport(hl);
                    car.cabin.teleport(hl);

                    if (driven && particles && tick % 3 == 0) {
                        double speed = car.horse.getVelocity().clone().setY(0).length();
                        if (speed > 0.1) {
                            Location back = hl.clone().subtract(hl.getDirection().setY(0).normalize().multiply(1.6)).add(0, 0.4, 0);
                            back.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, back, 1, 0.05, 0.05, 0.05, 0.01);
                        }
                    }
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    // ------------------------------------------------------------------ commands

    private void sendHelp(CommandSender s) {
        s.sendMessage(Component.text("/supercar menu | list [brand] | give <car> [player] | remove | reload"));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "menu" -> {
                if (!sender.hasPermission("supercars.admin")) {
                    sender.sendMessage(msg("no-permission"));
                    return true;
                }
                if (sender instanceof Player p) openGarage(p, 0);
                else sender.sendMessage(Component.text("Players only."));
            }
            case "list" -> {
                String filter = args.length >= 2 ? String.join(" ", List.of(args).subList(1, args.length)) : null;
                if (filter == null) {
                    Map<String, Integer> counts = new LinkedHashMap<>();
                    for (CarType t : carTypes.values()) counts.merge(t.brand(), 1, Integer::sum);
                    sender.sendMessage(Component.text("Brands (" + carTypes.size() + " cars). Use /supercar list <brand>:"));
                    counts.forEach((b, n) -> sender.sendMessage(Component.text(" - " + b + " (" + n + ")")));
                } else {
                    int shown = 0;
                    for (CarType t : carTypes.values()) {
                        if (t.brand().equalsIgnoreCase(filter)) {
                            sender.sendMessage(Component.text(" - " + t.id() + "  (" + t.name() + ")"));
                            shown++;
                        }
                    }
                    if (shown == 0) sender.sendMessage(Component.text("No cars found for brand: " + filter));
                }
            }
            case "give" -> {
                if (!sender.hasPermission("supercars.admin")) {
                    sender.sendMessage(msg("no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(Component.text("/supercar give <car> [player]"));
                    return true;
                }
                CarType type = carTypes.get(args[1].toLowerCase(Locale.ROOT));
                if (type == null) {
                    sender.sendMessage(msg("unknown-car"));
                    return true;
                }
                Player target = args.length >= 3 ? getServer().getPlayerExact(args[2])
                        : (sender instanceof Player pl ? pl : null);
                if (target == null) {
                    sender.sendMessage(Component.text("Player not found."));
                    return true;
                }
                giveItem(target, createCarItem(type));
                sender.sendMessage(msg("given", Map.of("%car%", type.name(), "%player%", target.getName())));
            }
            case "remove" -> {
                if (!sender.hasPermission("supercars.admin")) {
                    sender.sendMessage(msg("no-permission"));
                    return true;
                }
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(Component.text("Players only."));
                    return true;
                }
                int n = 0;
                for (ActiveCar car : new ArrayList<>(activeCars.values())) {
                    if (car.horse.getWorld().equals(p.getWorld())
                            && car.horse.getLocation().distanceSquared(p.getLocation()) <= 25) {
                        removeCar(car);
                        n++;
                    }
                }
                sender.sendMessage(Component.text("Removed " + n + " car(s) within 5 blocks."));
            }
            case "reload" -> {
                if (!sender.hasPermission("supercars.admin")) {
                    sender.sendMessage(msg("no-permission"));
                    return true;
                }
                reloadConfig();
                loadCars();
                sender.sendMessage(Component.text("SuperCars reloaded (" + carTypes.size() + " cars)."));
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("menu", "list", "give", "remove", "reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return carTypes.keySet().stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("list")) {
            return carTypes.values().stream().map(CarType::brand).distinct()
                    .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return getServer().getOnlinePlayers().stream().map(Player::getName)
                    .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
        }
        return List.of();
    }
}
