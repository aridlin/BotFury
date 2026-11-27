use crate::game::{Game, Stack};
use botfury_headless_worker::coordinator::{
    Assignment, ChestReport, Coordinator, ItemCounts, Kind, Renewed,
};
use serde::Deserialize;
use serde_json::{Value, json};
use std::{
    collections::BTreeMap,
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

#[derive(Clone, Copy, Deserialize)]
struct Pos {
    x: i32,
    y: i32,
    z: i32,
}

impl From<[i32; 3]> for Pos {
    fn from([x, y, z]: [i32; 3]) -> Self {
        Self { x, y, z }
    }
}

impl Pos {
    fn contains(self, min: Self, max: Self) -> bool {
        (min.x..=max.x).contains(&self.x)
            && (min.y..=max.y).contains(&self.y)
            && (min.z..=max.z).contains(&self.z)
    }

    fn distance_squared(self, [x, y, z]: [f64; 3]) -> f64 {
        (f64::from(self.x) + 0.5 - x).powi(2)
            + (f64::from(self.y) - y).powi(2)
            + (f64::from(self.z) + 0.5 - z).powi(2)
    }
}

#[derive(Deserialize)]
struct Bounds {
    min: Pos,
    max: Pos,
}

#[derive(Deserialize)]
struct Gather {
    resource: String,
    variant: Option<String>,
    item: String,
    count: u32,
    exclude: Option<Bounds>,
}

#[derive(Deserialize)]
struct Craft {
    recipe: String,
    count: u32,
    #[serde(default)]
    inventory: bool,
    block: Option<String>,
    target: Option<Pos>,
    stand: Option<Pos>,
}

#[derive(Deserialize)]
struct Smelt {
    block: String,
    target: Pos,
    stand: Pos,
    recipe: String,
    input: String,
    fuel: String,
    count: u32,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Transfer {
    block: String,
    direction: TransferDirection,
    item: String,
    stock: u32,
    target: Pos,
    stand: Pos,
    partner: Option<Pos>,
    stock_reserve: Option<u32>,
}

#[derive(Clone, Copy, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum TransferDirection {
    Deposit,
    Take,
    VerifyStock,
}

#[derive(Clone, Copy, Deserialize, serde::Serialize)]
#[serde(rename_all = "lowercase")]
enum Face {
    Down,
    Up,
    North,
    South,
    West,
    East,
}

impl Face {
    fn support(self, Pos { x, y, z }: Pos) -> Pos {
        let (dx, dy, dz) = match self {
            Self::Down => (0, 1, 0),
            Self::Up => (0, -1, 0),
            Self::North => (0, 0, 1),
            Self::South => (0, 0, -1),
            Self::West => (1, 0, 0),
            Self::East => (-1, 0, 0),
        };
        Pos {
            x: x + dx,
            y: y + dy,
            z: z + dz,
        }
    }

    fn axis(self) -> &'static str {
        match self {
            Self::Down | Self::Up => "y",
            Self::North | Self::South => "z",
            Self::West | Self::East => "x",
        }
    }
}

#[derive(Deserialize)]
struct Placement {
    target: Pos,
    stand: Pos,
    block: String,
    properties: Option<BTreeMap<String, String>>,
    face: Option<Face>,
}

impl Placement {
    fn matches(&self, actual: &Value) -> Result<bool, String> {
        let expected = vanilla(&self.block)?;
        let name = actual["block"]
            .as_str()
            .ok_or("Target block is outside loaded chunks")?;
        if expected == "air" {
            return Ok(is_air(name));
        }
        Ok(name == expected
            && self.properties.as_ref().is_none_or(|properties| {
                properties
                    .iter()
                    .all(|(key, value)| actual["properties"][key] == value.as_str())
            }))
    }
}

pub struct Outcome {
    pub success: bool,
    pub evidence: String,
    pub chest: Option<ChestReport>,
}

pub fn execute(
    coordinator: &Coordinator,
    assignment: &Assignment,
    game: &mut Game,
) -> Result<Outcome, String> {
    let payload = &assignment.job.work.payload;
    let mut job = Job {
        coordinator,
        assignment,
        game,
    };
    match assignment.job.work.kind {
        Kind::Gather => job.gather(parse(payload)?),
        Kind::Craft => job.craft(parse(payload)?),
        Kind::Smelt => job.smelt(parse(payload)?),
        Kind::Transfer => job.transfer(parse(payload)?),
        Kind::Build => job.place(parse(payload)?),
        Kind::Verify => job.verify(parse(payload)?),
    }
}

struct Job<'a> {
    coordinator: &'a Coordinator,
    assignment: &'a Assignment,
    game: &'a mut Game,
}

impl Job<'_> {
    /// Renew before each bounded gameplay action; fail closed if ownership is no longer confirmed.
    fn act(&mut self, name: &str, params: Value) -> Result<Value, String> {
        self.renew()?;
        self.game.call(name, params)
    }

    fn renew(&mut self) -> Result<(), String> {
        match self
            .coordinator
            .renew(self.assignment)
            .map_err(|error| format!("Renew failed: {error:?}"))?
        {
            Renewed::Active(lease) => {
                let now = SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .map_err(|_| "Worker clock is before the Unix epoch")?;
                // Bound the action by the observed lease, even if a slow reply ate into its time.
                if u128::from(lease.expires_at) <= now.as_millis() + 8_000 {
                    return Err(
                        "The renewed lease is too short to safely start a game action".into(),
                    );
                }
                Ok(())
            }
            Renewed::Lost => Err("Job lease is no longer active".into()),
        }
    }

    fn move_to(&mut self, Pos { x, y, z }: Pos) -> Result<(), String> {
        let result = self.act(
            "walk_to",
            json!({"x": x as f64 + 0.5, "y": y, "z":z as f64 + 0.5, "timeout_s": 7}),
        )?;
        if result["result"] != "arrived" {
            return Err(format!(
                "Could not reach the work position: {}",
                result["result"]
            ));
        }
        Ok(())
    }

    fn nearby_players(&mut self) -> Result<Vec<[f64; 3]>, String> {
        let view = self
            .game
            .call("entities", json!({"type":"player","radius":8,"limit":20}))?;
        let entities = view["entities"]
            .as_array()
            .ok_or("Missing player observations")?;
        entities
            .iter()
            .map(|entity| {
                serde_json::from_value(entity["pos"].clone()).map_err(|error| error.to_string())
            })
            .collect()
    }

    fn approach_pickup(&mut self, target: Pos, exclude: Option<&Bounds>) -> Result<(), String> {
        let others = self.nearby_players()?;
        let mut stands = Self::pickup_stands(target);
        let clearance = |stand: &Pos| {
            others
                .iter()
                .map(|other| stand.distance_squared(*other))
                .fold(f64::INFINITY, f64::min)
        };
        stands.sort_by(|left, right| clearance(right).total_cmp(&clearance(left)));
        for stand in stands {
            if exclude.is_some_and(|area| stand.contains(area.min, area.max)) {
                continue;
            }
            let block = |game: &mut Game, dy| {
                game.call(
                    "block_at",
                    json!({"x":stand.x,"y":stand.y + dy,"z":stand.z}),
                )
            };
            if block(self.game, 0)?["block"] != "air" || block(self.game, 1)?["block"] != "air" {
                continue;
            }
            let floor = block(self.game, -1)?;
            if matches!(
                floor["block"].as_str(),
                None | Some("air" | "lava" | "water")
            ) {
                continue;
            }
            self.move_to(stand)?;
            return self.wait_for_pickup_clearance(target);
        }
        Err("No clear pickup approach beside the block".into())
    }

    fn pickup_stands(target: Pos) -> [Pos; 4] {
        [(1, 0), (-1, 0), (0, 1), (0, -1)].map(|(x, z)| Pos {
            x: target.x + x,
            z: target.z + z,
            ..target
        })
    }

    fn wait_for_pickup_clearance(&mut self, target: Pos) -> Result<(), String> {
        for _ in 0..12 {
            let crowded = self
                .nearby_players()?
                .into_iter()
                .any(|other| target.distance_squared(other) < 1.4_f64.powi(2));
            if !crowded {
                return Ok(());
            }
            thread::sleep(Duration::from_millis(125));
        }
        Err("Another player is within pickup range of the block".into())
    }

    fn gather_approach(
        &mut self,
        search_name: &str,
        blocks: &[&str],
        exclude: Option<&Bounds>,
    ) -> Result<Pos, String> {
        let radius = if self.game.baritone { 12 } else { 4 };
        let mut query = json!({"name": search_name, "radius":radius, "limit":64});
        if self.game.baritone {
            query["exposed_names"] = json!(blocks);
        }
        let search = self.game.call("find_blocks", query)?;
        let found = search["found"]
            .as_array()
            .ok_or("Missing block search results")?;
        let usable = |pos: Pos| !exclude.is_some_and(|area| pos.contains(area.min, area.max));
        let mut targets = found
            .iter()
            .filter(|candidate| {
                candidate["block"]
                    .as_str()
                    .is_some_and(|name| blocks.contains(&name))
            })
            .filter_map(|candidate| {
                serde_json::from_value::<[i32; 3]>(candidate["pos"].clone()).ok()
            })
            .map(Pos::from)
            .filter(|&pos| usable(pos));
        if !self.game.baritone {
            let target = targets
                .next()
                .ok_or("No matching block within local reach")?;
            self.approach_pickup(target, exclude)?;
            return Ok(target);
        }

        let others = self.nearby_players()?;
        let mut stands = BTreeMap::new();
        for target in targets
            .filter(|target| {
                !others
                    .iter()
                    .any(|other| target.distance_squared(*other) < 1.4_f64.powi(2))
            })
            .take(24)
        {
            for stand in Self::pickup_stands(target)
                .into_iter()
                .filter(|&pos| usable(pos))
            {
                stands.entry([stand.x, stand.y, stand.z]).or_insert(target);
            }
        }
        if stands.is_empty() {
            return Err("No matching block with an available approach in loaded terrain".into());
        }
        let choices: Vec<_> = stands.keys().collect();
        let response = self
            .game
            .call("baritone_approach", json!({"stands":choices}))?;
        let stand = serde_json::from_value::<Option<[i32; 3]>>(response["stand"].clone())
            .map_err(|error| error.to_string())?
            .ok_or("Baritone found no safe route to a matching block")?;
        let target = *stands
            .get(&stand)
            .ok_or("Baritone returned an unknown stand")?;
        self.move_to(stand.into())?;
        self.wait_for_pickup_clearance(target)?;
        Ok(target)
    }

    fn gather(&mut self, work: Gather) -> Result<Outcome, String> {
        let item = vanilla(&work.item)?;
        let variant = work.variant.as_deref().unwrap_or("both");
        let (search, blocks, tier): (&str, &[&str], u8) = match (work.resource.as_str(), item) {
            ("stone", "cobblestone") => ("stone", &["stone"], 1),
            ("deepslate", "cobbled_deepslate") => ("deepslate", &["deepslate"], 1),
            ("coal", "coal") => (
                "coal_ore",
                ore_variants(variant, &["coal_ore", "deepslate_coal_ore"])?,
                1,
            ),
            ("iron", "raw_iron") => (
                "iron_ore",
                ore_variants(variant, &["iron_ore", "deepslate_iron_ore"])?,
                2,
            ),
            ("diamond", "diamond") => (
                "diamond_ore",
                ore_variants(variant, &["diamond_ore", "deepslate_diamond_ore"])?,
                3,
            ),
            ("dirt" | "block:minecraft:dirt", "dirt") => ("dirt", &["dirt"], 0),
            (resource, item)
                if resource.strip_prefix("block:minecraft:") == Some(item)
                    && (item.ends_with("_log") || item.ends_with("_stem")) =>
            {
                (item, &[item], 0)
            }
            _ => return Err("Unsupported gathering resource and output item".into()),
        };
        if !(1..=576).contains(&work.count) {
            return Err("Invalid gather count".into());
        }
        while self
            .game
            .inventory()?
            .counts()
            .get(&work.item)
            .copied()
            .unwrap_or(0)
            < work.count
        {
            if tier > 0 {
                let inventory = self.game.inventory()?;
                let pickaxe = inventory
                    .slots
                    .iter()
                    .filter(|slot| (9..45).contains(&slot.slot) && pickaxe_tier(&slot.item) >= tier)
                    .max_by_key(|slot| pickaxe_tier(&slot.item))
                    .ok_or_else(|| {
                        let minimum = ["", "wooden", "stone", "iron"][usize::from(tier)];
                        format!(
                            "Need a {minimum} pickaxe or better to gather {}",
                            work.resource
                        )
                    })?;
                self.select_item(pickaxe.slot, &pickaxe.item)?;
            }
            let pos = self.gather_approach(search, blocks, work.exclude.as_ref())?;
            let actual = self.block_at(pos)?;
            let block = actual["block"]
                .as_str()
                .filter(|name| blocks.contains(name))
                .ok_or("The target block changed while approaching it")?;
            let old_count = self
                .game
                .inventory()?
                .counts()
                .get(&work.item)
                .copied()
                .unwrap_or(0);
            let broken = self.act("dig", json!({"x":pos.x,"y":pos.y,"z":pos.z,"timeout_s":7}))?;
            if broken["broken"] != true || broken["before"] != block || broken["after"] != "air" {
                return Err("Server did not confirm the expected block was mined".into());
            }
            self.move_to(pos)?;
            for _ in 0..8 {
                if self
                    .game
                    .inventory()?
                    .counts()
                    .get(&work.item)
                    .copied()
                    .unwrap_or(0)
                    > old_count
                {
                    break;
                }
                thread::sleep(Duration::from_millis(125));
            }
            if self
                .game
                .inventory()?
                .counts()
                .get(&work.item)
                .copied()
                .unwrap_or(0)
                <= old_count
            {
                return Ok(Outcome {
                    success: false,
                    evidence: "Mined block but did not collect the drop".into(),
                    chest: None,
                });
            }
        }
        Ok(observed(format!("Observed {} {}", work.count, work.item)))
    }

    fn click(&mut self, slot: usize, button: u8, mode: &str) -> Result<(), String> {
        let await_pickup = self.game.baritone && mode == "pickup";
        self.act(
            "click_slot",
            json!({"slot":slot,"button":button,"mode":mode,"await_pickup":await_pickup}),
        )?;
        Ok(())
    }

    fn block_at(&mut self, Pos { x, y, z }: Pos) -> Result<Value, String> {
        self.game.call("block_at", json!({"x": x, "y": y, "z": z}))
    }

    fn place(&mut self, work: Placement) -> Result<Outcome, String> {
        let actual = self.block_at(work.target)?;
        if work.matches(&actual)? {
            return Ok(observed(format!("Observed existing {}", work.block)));
        }
        if !actual["block"].as_str().is_some_and(is_air) {
            return Err(format!(
                "Placement target already contains {}",
                actual["block"]
            ));
        }
        let face = work.face.unwrap_or(Face::Up);
        if let Some(properties) = &work.properties
            && !properties.is_empty()
            && (properties.len() != 1
                || properties
                    .get("axis")
                    .is_none_or(|axis| axis != face.axis()))
        {
            return Err(
                "Requested block properties are unsupported by this placement action".into(),
            );
        }

        self.move_to(work.stand)?;
        let support = face.support(work.target);
        if self.block_at(support)?["block"].as_str().is_none_or(is_air) {
            return Err("Placement support is missing".into());
        }
        let item = vanilla(&work.block)?;
        let inventory = self.game.inventory()?;
        let slot = inventory
            .slots
            .iter()
            .filter(|stack| (9..45).contains(&stack.slot) && stack.item == item)
            .max_by_key(|stack| stack.slot)
            .ok_or_else(|| format!("Missing material {}", work.block))?
            .slot;
        self.select_item(slot, item)?;
        let current = self.block_at(work.target)?;
        if work.matches(&current)? {
            return Ok(observed(format!("Observed existing {}", work.block)));
        }
        if !current["block"].as_str().is_some_and(is_air) {
            return Err("Placement target changed while the worker was approaching".into());
        }
        let Pos { x, y, z } = support;
        self.act("use_block", json!({"x": x, "y": y, "z": z, "face": face}))?;
        self.verify(work)
    }

    fn verify(&mut self, work: Placement) -> Result<Outcome, String> {
        let actual = self.block_at(work.target)?;
        if !work.matches(&actual)? {
            return Err(format!(
                "Server block does not match {}: {actual}",
                work.block
            ));
        }
        let Pos { x, y, z } = work.target;
        Ok(observed(format!(
            "Observed {} at {x}, {y}, {z}",
            work.block
        )))
    }

    fn select_item(&mut self, slot: usize, item: &str) -> Result<(), String> {
        let hotbar = if slot >= 36 {
            slot - 36
        } else {
            self.click(slot, 0, "swap")?;
            0
        };
        self.act("select_slot", json!({"slot": hotbar}))?;
        if !self
            .game
            .inventory()?
            .slots
            .iter()
            .any(|stack| stack.slot == 36 + hotbar && stack.item == item)
        {
            return Err("Server did not confirm the selected item".into());
        }
        Ok(())
    }

    fn craft(&mut self, work: Craft) -> Result<Outcome, String> {
        let recipe = vanilla(&work.recipe)?;
        let wood = recipe.strip_suffix("_planks").unwrap_or_default();
        let log = format!("{wood}_log");
        let stem = format!("{wood}_stem");
        let (pattern, output, table) = match recipe {
            _ if !wood.is_empty() => ("L", 4, false),
            "stick" => ("P P ", 4, false),
            "crafting_table" => ("PPPP", 1, false),
            "wooden_pickaxe" => ("PPP S  S ", 1, true),
            "stone_pickaxe" => ("CCC S  S ", 1, true),
            "furnace" => ("CCCC CCCC", 1, true),
            "iron_pickaxe" => ("III S  S ", 1, true),
            _ => return Err("Unsupported crafting recipe".into()),
        };
        if !(1..=64).contains(&work.count) || table == work.inventory {
            return Err("Unexpected crafting station or output count".into());
        }

        let mut inventory = self.game.inventory()?;
        let count = |inventory: &crate::game::Inventory| {
            inventory
                .slots
                .iter()
                .filter(|s| (9..45).contains(&s.slot) && s.item == recipe)
                .map(|s| s.count)
                .sum::<u32>()
        };
        if count(&inventory) >= work.count {
            return Ok(observed(format!("Observed {} {}", work.count, work.recipe)));
        }
        if table {
            let target = work.target.ok_or("Crafting table position is missing")?;
            let stand = work.stand.ok_or("Crafting table approach is missing")?;
            if work.block.as_deref() != Some("minecraft:crafting_table") {
                return Err("Expected a crafting table".into());
            }
            self.move_to(stand)?;
            if self.block_at(target)?["block"] != "crafting_table" {
                return Err("The crafting table is missing".into());
            }
            self.act("use_block", json!({"x":target.x,"y":target.y,"z":target.z}))?;
            inventory = self.game.inventory()?;
        }

        while count(&inventory) < work.count {
            if !self.craft_grid(table, &inventory)?.is_empty() {
                return Err("The crafting grid already has items".into());
            }
            let mut sources: Vec<_> = inventory
                .slots
                .iter()
                .filter(|s| (9..45).contains(&s.slot))
                .map(|s| (s, s.count))
                .collect();
            let mut placements: BTreeMap<usize, (u32, Vec<usize>)> = BTreeMap::new();
            for (offset, ingredient) in pattern.bytes().enumerate().filter(|&(_, id)| id != b' ') {
                let (source, remaining) = sources
                    .iter_mut()
                    .find(|(s, left)| {
                        *left > 0
                            && match ingredient {
                                b'L' => s.item == log || s.item == stem,
                                b'P' => s.item.ends_with("_planks"),
                                b'S' => s.item == "stick",
                                b'C' => s.item == "cobblestone",
                                b'I' => s.item == "iron_ingot",
                                _ => false,
                            }
                    })
                    .ok_or_else(|| format!("Missing ingredients for {}", work.recipe))?;
                *remaining -= 1;
                placements
                    .entry(source.slot)
                    .or_insert_with(|| (source.count, Vec::new()))
                    .1
                    .push(offset + 1);
            }

            for (slot, (available, destinations)) in placements {
                let source = if table { slot + 1 } else { slot };
                self.click(source, 0, "pickup")?;
                let mut left = available;
                for destination in destinations {
                    self.click(destination, u8::from(left > 1), "pickup")?;
                    left -= 1;
                }
                if left > 0 {
                    self.click(source, 0, "pickup")?;
                }
            }
            let staged = self.game.inventory()?;
            if !self
                .craft_grid(table, &staged)?
                .iter()
                .any(|s| s.slot == 0 && s.item == recipe && s.count == output)
            {
                return Err("Server did not offer the expected crafting output".into());
            }
            self.click(0, 0, "quick_move")?;
            let crafted = self.game.inventory()?;
            if count(&crafted) != count(&inventory) + output
                || !self.craft_grid(table, &crafted)?.is_empty()
            {
                return Err("Server did not confirm the crafted output and empty grid".into());
            }
            inventory = crafted;
        }
        if table {
            self.act("close_menu", json!({}))?;
        }
        Ok(observed(format!("Observed {} {}", work.count, work.recipe)))
    }

    fn craft_grid(
        &mut self,
        table: bool,
        inventory: &crate::game::Inventory,
    ) -> Result<Vec<Stack>, String> {
        if table {
            let menu = self.game.call("menu", json!({}))?;
            if menu["type"] != "crafting"
                || menu["container_slots"] != 10
                || menu["total_slots"] != 46
            {
                return Err("Did not open the expected crafting grid".into());
            }
            return serde_json::from_value(menu["items"].clone())
                .map_err(|error| error.to_string());
        }
        Ok(inventory
            .slots
            .iter()
            .filter(|s| s.slot < 5)
            .cloned()
            .collect())
    }

    fn smelt(&mut self, work: Smelt) -> Result<Outcome, String> {
        let input = vanilla(&work.input)?;
        let fuel = vanilla(&work.fuel)?;
        let burn_ticks: u32 = match fuel {
            "coal" | "charcoal" => 1600,
            _ => return Err("Unsupported furnace fuel".into()),
        };
        let output = match (vanilla(&work.recipe)?, input) {
            ("iron_ingot_from_smelting_raw_iron", "raw_iron") => "iron_ingot",
            _ => return Err("Unsupported furnace recipe and input".into()),
        };
        if work.block != "minecraft:furnace" || !(1..=64).contains(&work.count) {
            return Err("Invalid furnace or smelting count".into());
        }
        let output_id = format!("minecraft:{output}");
        let count_output = |items: &ItemCounts| items.get(&output_id).copied().unwrap_or(0);
        let carried = self.game.inventory()?.counts();
        if count_output(&carried) >= work.count {
            return Ok(observed(format!("Observed {} {output_id}", work.count)));
        }
        self.move_to(work.stand)?;
        if self.block_at(work.target)?["block"] != "furnace" {
            return Err("Expected furnace is missing".into());
        }
        let Pos { x, y, z } = work.target;
        self.act("use_block", json!({"x":x,"y":y,"z":z}))?;

        let (slots, credited_ticks) = self.furnace_state([input, fuel, output])?;
        let need = work.count - count_output(&carried);
        let queued = slots[0] + slots[2];
        if queued > need {
            return Err("The furnace has more input or output than this job needs".into());
        }
        let input_to_load = need - queued;
        let fuel_to_load = ((need - slots[2]) * 200)
            .saturating_sub(credited_ticks + slots[1] * burn_ticks)
            .div_ceil(burn_ticks);
        if carried.get(&work.input).copied().unwrap_or(0) < input_to_load {
            return Err(format!(
                "Need {input_to_load} {} for the furnace",
                work.input
            ));
        }
        if carried.get(&work.fuel).copied().unwrap_or(0) < fuel_to_load {
            return Err(format!("Need {fuel_to_load} {} for the furnace", work.fuel));
        }

        self.load_furnace_slot(fuel, 1, fuel_to_load)?;
        self.load_furnace_slot(input, 0, input_to_load)?;
        let mut latest_output = Instant::now();
        let mut last_renewal = latest_output;
        let mut seen_output = slots[2];
        loop {
            let (slots, _) = self.furnace_state([input, fuel, output])?;
            if slots[2] == need {
                self.click(2, 0, "quick_move")?;
                let after = self.game.inventory()?.counts();
                if count_output(&after) != work.count {
                    return Err(
                        "Server did not confirm the full smelted output was collected".into(),
                    );
                }
                self.act("close_menu", json!({}))?;
                return Ok(observed(format!("Observed {} {output_id}", work.count)));
            }
            if slots[2] > seen_output {
                seen_output = slots[2];
                latest_output = Instant::now();
            }
            if latest_output.elapsed() >= Duration::from_secs(30) {
                return Err(format!("The furnace stopped producing {output_id}"));
            }
            if last_renewal.elapsed() >= Duration::from_secs(5) {
                self.renew()?;
                last_renewal = Instant::now();
            }
            thread::sleep(Duration::from_millis(150));
        }
    }

    fn furnace_state(&mut self, expected: [&str; 3]) -> Result<([u32; 3], u32), String> {
        let menu = self.game.call("menu", json!({}))?;
        if menu["type"] != "furnace" || menu["container_slots"] != 3 || menu["total_slots"] != 39 {
            return Err("Did not open the expected furnace".into());
        }
        let mut counts = [0; 3];
        let items: Vec<Stack> =
            serde_json::from_value(menu["items"].clone()).map_err(|error| error.to_string())?;
        for stack in items {
            if expected.get(stack.slot) != Some(&stack.item.as_str()) {
                return Err("The furnace contains unrelated items".into());
            }
            counts[stack.slot] = stack.count;
        }
        let ticks = ["burn_ticks", "cook_ticks"]
            .into_iter()
            .filter_map(|key| menu[key].as_u64())
            .filter_map(|ticks| u16::try_from(ticks).ok())
            .map(u32::from)
            .sum();
        Ok((counts, ticks))
    }

    fn load_furnace_slot(
        &mut self,
        item: &str,
        destination: usize,
        mut amount: u32,
    ) -> Result<(), String> {
        while amount > 0 {
            let inventory = self.game.inventory()?;
            let source = inventory
                .slots
                .iter()
                .find(|s| (9..45).contains(&s.slot) && s.item == item)
                .ok_or_else(|| format!("Missing {item} for the furnace"))?;
            let slot = source.slot - 6;
            self.click(slot, 0, "pickup")?;
            if source.count <= amount {
                self.click(destination, 0, "pickup")?;
                amount -= source.count;
            } else {
                for _ in 0..amount {
                    self.click(destination, 1, "pickup")?;
                }
                self.click(slot, 0, "pickup")?;
                amount = 0;
            }
            self.game.inventory()?;
        }
        Ok(())
    }

    fn transfer(&mut self, work: Transfer) -> Result<Outcome, String> {
        let item = vanilla(&work.item)?;
        let chest_slots = if work.partner.is_some() { 54 } else { 27 };
        if work.block != "minecraft:chest" || work.stock > (chest_slots * 64) as u32 {
            return Err("Invalid chest transfer".into());
        }
        self.move_to(work.stand)?;
        if self.block_at(work.target)?["block"] != "chest" {
            return Err("Expected chest is missing".into());
        }
        if let Some(partner) = work.partner
            && self.block_at(partner)?["block"] != "chest"
        {
            return Err("Expected double-chest partner is missing".into());
        }
        let Pos { x, y, z } = work.target;
        self.act("use_block", json!({"x":x,"y":y,"z":z}))?;

        let deposit = matches!(work.direction, TransferDirection::Deposit);
        let mut previous: Option<(u32, u32, u32)> = None;
        loop {
            let menu = self.game.call("menu", json!({}))?;
            let expected_type = if chest_slots == 54 {
                "generic_9x6"
            } else {
                "generic_9x3"
            };
            if menu["type"] != expected_type
                || menu["container_slots"] != chest_slots
                || menu["total_slots"] != chest_slots + 36
            {
                return Err("Did not open the expected chest".into());
            }
            let chest: Vec<Stack> =
                serde_json::from_value(menu["items"].clone()).map_err(|error| error.to_string())?;
            let mut items = ItemCounts::new();
            for stack in &chest {
                *items
                    .entry(format!("minecraft:{}", stack.item))
                    .or_default() += stack.count;
            }
            let stored = items.get(&work.item).copied().unwrap_or(0);
            let inventory = self.game.inventory()?;
            let carried = inventory.counts().get(&work.item).copied().unwrap_or(0);
            if let Some((prior_stored, prior_carried, maximum)) = previous {
                let (chest_delta, cargo_delta) = if deposit {
                    (
                        stored.checked_sub(prior_stored),
                        prior_carried.checked_sub(carried),
                    )
                } else {
                    (
                        prior_stored.checked_sub(stored),
                        carried.checked_sub(prior_carried),
                    )
                };
                if chest_delta != cargo_delta
                    || !chest_delta.is_some_and(|delta| (1..=maximum).contains(&delta))
                {
                    return Err(
                        "Server did not confirm the matching chest and cargo changes".into(),
                    );
                }
            }
            let measured = if matches!(work.direction, TransferDirection::Take) {
                carried
            } else {
                stored
            };
            let remaining = work.stock.saturating_sub(measured);
            if remaining == 0 || matches!(work.direction, TransferDirection::VerifyStock) {
                self.act("close_menu", json!({}))?;
                self.game.inventory()?;
                let location = if matches!(work.direction, TransferDirection::Take) {
                    "in worker inventory"
                } else {
                    "in storage"
                };
                return Ok(Outcome {
                    success: remaining == 0,
                    evidence: format!(
                        "Observed {measured}/{} {} {location}",
                        work.stock, work.item
                    ),
                    chest: Some(ChestReport {
                        location: format!("block:{x},{y},{z}"),
                        items,
                        free: None,
                    }),
                });
            }
            if !deposit
                && stored
                    .checked_sub(remaining)
                    .is_none_or(|left| left < work.stock_reserve.unwrap_or(0))
            {
                return Err(format!(
                    "Chest cannot supply {remaining} {} while preserving its reserve",
                    work.item
                ));
            }
            let personal: Vec<_> = inventory
                .slots
                .iter()
                .filter(|s| (9..45).contains(&s.slot))
                .collect();
            let sources: Vec<_> = if deposit {
                personal.clone()
            } else {
                chest.iter().collect()
            };
            let source = sources
                .iter()
                .filter(|s| s.item == item)
                .max_by_key(|s| s.count.min(remaining))
                .ok_or("No matching item is available for transfer")?;
            let from = if deposit {
                chest_slots + source.slot - 9
            } else {
                source.slot
            };
            let maximum = if source.count <= remaining {
                self.click(from, 0, "quick_move")?;
                source.count
            } else {
                let to = if deposit {
                    (0..chest_slots).find(|i| chest.iter().all(|stack| stack.slot != *i))
                } else {
                    (9..45)
                        .find(|i| personal.iter().all(|stack| stack.slot != *i))
                        .map(|i| chest_slots + i - 9)
                }
                .ok_or("An empty destination slot is required to split the stack")?;
                let split = remaining.min(8);
                self.click(from, 0, "pickup")?;
                for _ in 0..split {
                    self.click(to, 1, "pickup")?;
                }
                self.click(from, 0, "pickup")?;
                split
            };
            previous = Some((stored, carried, maximum));
        }
    }
}

fn ore_variants<'a>(variant: &str, both: &'a [&str; 2]) -> Result<&'a [&'a str], String> {
    match variant {
        "both" => Ok(both),
        "normal" => Ok(&both[..1]),
        "deepslate" => Ok(&both[1..]),
        _ => Err(format!("Unsupported ore variant {variant}")),
    }
}

fn pickaxe_tier(item: &str) -> u8 {
    match item {
        "wooden_pickaxe" | "golden_pickaxe" => 1,
        "stone_pickaxe" => 2,
        "iron_pickaxe" | "diamond_pickaxe" | "netherite_pickaxe" => 3,
        _ => 0,
    }
}

fn vanilla(item: &str) -> Result<&str, String> {
    item.strip_prefix("minecraft:")
        .ok_or_else(|| format!("Expected an exact vanilla item: {item}"))
}

fn is_air(block: &str) -> bool {
    matches!(block, "air" | "cave_air" | "void_air")
}

fn parse<T: serde::de::DeserializeOwned>(payload: &str) -> Result<T, String> {
    serde_json::from_str(payload).map_err(|error| error.to_string())
}

fn observed(evidence: String) -> Outcome {
    Outcome {
        success: true,
        evidence,
        chest: None,
    }
}
