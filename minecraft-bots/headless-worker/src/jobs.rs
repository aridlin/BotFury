use crate::game::{Game, Stack};
use botfury_headless_worker::coordinator::{
    Assignment, ChestReport, Coordinator, ItemCounts, Kind, Renewed,
};
use serde::Deserialize;
use serde_json::{Value, json};
use std::{
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

pub struct Outcome {
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
            let search = self.game.call(
                "find_blocks",
                json!({"name": block, "radius":4, "limit":64}),
            )?;
            let found = search["found"]
                .as_array()
                .ok_or("Missing block search results")?;
            let pos = found
                .iter()
                .filter(|candidate| candidate["block"] == block)
                .filter_map(|candidate| {
                    serde_json::from_value::<[i32; 3]>(candidate["pos"].clone()).ok()
                })
                .map(Pos::from)
                .find(|pos| {
                    !work
                        .exclude
                        .as_ref()
                        .is_some_and(|area| pos.contains(area.min, area.max))
                })
                .ok_or("No matching block within local reach")?;
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
                return Err("Server did not confirm collection".into());
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

fn parse<T: serde::de::DeserializeOwned>(payload: &str) -> Result<T, String> {
    serde_json::from_str(payload).map_err(|error| error.to_string())
}

fn observed(evidence: String) -> Outcome {
    Outcome {
        evidence,
        chest: None,
    }
}
