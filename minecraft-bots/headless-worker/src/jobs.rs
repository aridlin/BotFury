use crate::game::{Game, Stack};
use botfury_headless_worker::coordinator::{
    Assignment, ChestReport, Coordinator, ItemCounts, Kind, Renewed,
};
use serde::Deserialize;
use serde_json::{Value, json};
use std::{
    collections::BTreeMap,
    thread,
    time::{Duration, SystemTime, UNIX_EPOCH},
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
    item: String,
    count: u32,
    exclude: Option<Bounds>,
}

#[derive(Deserialize)]
struct Craft {
    recipe: String,
    count: u32,
    inventory: bool,
}

#[derive(Deserialize)]
struct Transfer {
    block: String,
    direction: String,
    item: String,
    stock: u32,
    target: Pos,
    stand: Pos,
    partner: Option<Pos>,
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
                self.game.call(name, params)
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

    fn gather_approach(&mut self, block: &str, exclude: Option<&Bounds>) -> Result<Pos, String> {
        let radius = if self.game.baritone { 12 } else { 4 };
        let search = self.game.call(
            "find_blocks",
            json!({"name": block, "radius":radius, "limit":64}),
        )?;
        let found = search["found"]
            .as_array()
            .ok_or("Missing block search results")?;
        let usable = |pos: Pos| !exclude.is_some_and(|area| pos.contains(area.min, area.max));
        let mut targets = found
            .iter()
            .filter(|candidate| candidate["block"] == block)
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
        let block = vanilla(
            work.resource
                .strip_prefix("block:")
                .ok_or("Expected an exact block resource")?,
        )?;
        if item != block
            || !(1..=64).contains(&work.count)
            || !(item.ends_with("_log") || item.ends_with("_stem") || item == "dirt")
        {
            return Err("Unsupported pilot gathering recipe".into());
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
            let pos = self.gather_approach(block, work.exclude.as_ref())?;
            if self.block_at(pos)?["block"] != block {
                return Err("The target block changed while approaching it".into());
            }
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
        self.act(
            "click_slot",
            json!({"slot":slot,"button":button,"mode":mode}),
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
            return Err("Server did not confirm the selected building material".into());
        }
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

    fn craft(&mut self, work: Craft) -> Result<Outcome, String> {
        let recipe = vanilla(&work.recipe)?;
        let wood = recipe
            .strip_suffix("_planks")
            .ok_or("Expected a plank recipe")?;
        if !work.inventory || !(1..=64).contains(&work.count) {
            return Err("Unsupported pilot crafting recipe".into());
        }
        let log = format!("{wood}_log");
        let stem = format!("{wood}_stem");
        loop {
            let inventory = self.game.inventory()?;
            if inventory.counts().get(&work.recipe).copied().unwrap_or(0) >= work.count {
                break;
            }
            if inventory.slots.iter().any(|stack| stack.slot <= 4) {
                return Err("The crafting grid already has items".into());
            }
            let source = inventory
                .slots
                .iter()
                .find(|stack| {
                    (9..45).contains(&stack.slot) && (stack.item == log || stack.item == stem)
                })
                .ok_or("No matching log to craft planks")?;
            self.click(source.slot, 0, "pickup")?;
            self.click(1, u8::from(source.count > 1), "pickup")?;
            if source.count > 1 {
                self.click(source.slot, 0, "pickup")?;
            }
            let staged = self.game.inventory_snapshot()?;
            if !staged
                .slots
                .iter()
                .any(|slot| slot.slot == 0 && slot.item == recipe && slot.count == 4)
            {
                return Err("Server did not offer the expected crafting output".into());
            }
            self.click(0, 0, "quick_move")?;
            let count = |slots: &[Stack]| {
                slots
                    .iter()
                    .filter(|s| (9..45).contains(&s.slot) && s.item == recipe)
                    .map(|s| s.count)
                    .sum::<u32>()
            };
            if count(&self.game.inventory()?.slots) != count(&inventory.slots) + 4 {
                return Err("Server did not confirm the crafted output".into());
            }
        }
        Ok(observed(format!("Observed {} {}", work.count, work.recipe)))
    }

    fn transfer(&mut self, work: Transfer) -> Result<Outcome, String> {
        let item = vanilla(&work.item)?;
        if work.block != "minecraft:chest"
            || work.direction != "DEPOSIT"
            || work.partner.is_some()
            || work.stock > 1728
        {
            return Err("Expected a single-chest deposit".into());
        }
        self.move_to(work.stand)?;
        let Pos { x, y, z } = work.target;
        let target = self.game.call("block_at", json!({"x":x,"y":y,"z":z}))?;
        if target["block"] != "chest" {
            return Err("Expected chest is missing".into());
        }
        self.act("use_block", json!({"x":x,"y":y,"z":z}))?;
        let mut previous_stock = None;
        loop {
            let menu = self.game.call("menu", json!({}))?;
            if menu["type"] != "generic_9x3"
                || menu["container_slots"] != 27
                || menu["total_slots"] != 63
            {
                return Err("Did not open the expected single chest".into());
            }
            let stacks: Vec<Stack> =
                serde_json::from_value(menu["items"].clone()).map_err(|error| error.to_string())?;
            let mut items = ItemCounts::new();
            for stack in &stacks {
                *items
                    .entry(format!("minecraft:{}", stack.item))
                    .or_default() += stack.count;
            }
            let current = items.get(&work.item).copied().unwrap_or(0);
            if previous_stock.is_some_and(|stock| current <= stock) {
                return Err("Server did not confirm the chest transfer".into());
            }
            if current >= work.stock {
                self.act("close_menu", json!({}))?;
                self.game.inventory()?;
                return Ok(Outcome {
                    success: true,
                    evidence: format!("Observed {current} {} in storage", work.item),
                    chest: Some(ChestReport {
                        location: format!("block:{x},{y},{z}"),
                        items,
                        free: None,
                    }),
                });
            }
            let inventory = self.game.inventory()?;
            let stack = inventory
                .slots
                .iter()
                .filter(|s| (9..45).contains(&s.slot) && s.item == item)
                .find(|s| s.count <= work.stock - current)
                .ok_or("No carried stack fits the remaining deposit")?;
            self.click(27 + stack.slot - 9, 0, "quick_move")?;
            previous_stock = Some(current);
        }
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
