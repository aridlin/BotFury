//! Adapter from the headless Minecraft chunk cache to the Baritone Rust pathfinder.
use crate::world::World;
use horizon_baritone::{
    baritone_astar::{AStarPathFinder, Status},
    baritone_block_state::{BlockState, Class, FluidKind},
    baritone_block_state_interface::BlockStateInterface,
    baritone_calc_path::Path,
    baritone_calculation_context::{BareHands, CalculationContext, PlayerFacts},
    baritone_goals::{GoalBlock, GoalComposite, GoalRef, GoalXZ, rc},
    baritone_settings::Settings,
};
use std::{cell::RefCell, collections::HashMap, rc::Rc, sync::Arc};

struct LoadedWorld<'a> {
    world: &'a World,
    states: RefCell<HashMap<u32, Rc<BlockState>>>,
    blocked: Rc<BlockState>,
}

impl<'a> LoadedWorld<'a> {
    fn new(world: &'a World) -> Self {
        Self {
            world,
            states: RefCell::new(HashMap::new()),
            blocked: Rc::new(BlockState {
                id: u32::MAX,
                name: Arc::from("minecraft:barrier"),
                classes: 0,
                props: Arc::from([]),
                fluid: FluidKind::None,
                fluid_amount: 0,
                hardness: -1.0,
                replaceable: false,
                full_collision: true,
                sturdy_top: false,
                pathfindable: false,
            }),
        }
    }

    fn standable(&self, [x, y, z]: [i32; 3]) -> bool {
        if !(self.min_y() + 1..self.max_y()).contains(&y) || !self.is_loaded(x, z) {
            return false;
        }
        let feet = self.get0(x, y, z);
        let low_snow = feet.is(Class::SnowLayer) && matches!(feet.prop("layers"), Some("1" | "2"));
        (feet.is_air() || low_snow)
            && self.get0(x, y + 1, z).is_air()
            && self.get0(x, y - 1, z).sturdy_top
    }

    fn mining_stands(&self, target: [i32; 3]) -> impl Iterator<Item = [i32; 3]> + '_ {
        let [x, y, z] = target;
        [0, -1, -2, 1]
            .into_iter()
            .flat_map(move |dy| {
                [(1, 0), (-1, 0), (0, 1), (0, -1)]
                    .into_iter()
                    .map(move |(dx, dz)| [x + dx, y + dy, z + dz])
            })
            .filter(move |&stand| {
                self.standable(stand) && self.can_see_mining_target(stand, target)
            })
    }

    fn can_see_mining_target(&self, stand: [i32; 3], target: [i32; 3]) -> bool {
        let eye = [
            f64::from(stand[0]) + 0.5,
            f64::from(stand[1]) + 1.62,
            f64::from(stand[2]) + 0.5,
        ];
        let center = target.map(|axis| f64::from(axis) + 0.5);
        let ray = std::array::from_fn::<_, 3, _>(|axis| center[axis] - eye[axis]);
        let lower: [i32; 3] =
            std::array::from_fn(|axis| eye[axis].min(center[axis]).floor() as i32);
        let upper: [i32; 3] =
            std::array::from_fn(|axis| eye[axis].max(center[axis]).floor() as i32);
        let mut boxes = Vec::new();
        for x in lower[0]..=upper[0] {
            for y in lower[1]..=upper[1] {
                for z in lower[2]..=upper[2] {
                    if [x, y, z] == target {
                        continue;
                    }
                    boxes.clear();
                    self.world.collision_boxes(x, y, z, &mut boxes);
                    if boxes
                        .iter()
                        .any(|b| intersects_segment(eye, ray, b.min, b.max))
                    {
                        return false;
                    }
                }
            }
        }
        true
    }
}

fn intersects_segment(eye: [f64; 3], ray: [f64; 3], min: [f64; 3], max: [f64; 3]) -> bool {
    let mut entry: f64 = 0.0;
    let mut exit: f64 = 1.0;
    for axis in 0..3 {
        if ray[axis] == 0.0 {
            if eye[axis] < min[axis] || eye[axis] > max[axis] {
                return false;
            }
            continue;
        }
        let first = (min[axis] - eye[axis]) / ray[axis];
        let second = (max[axis] - eye[axis]) / ray[axis];
        entry = entry.max(first.min(second));
        exit = exit.min(first.max(second));
        if entry > exit {
            return false;
        }
    }
    true
}

impl BlockStateInterface for LoadedWorld<'_> {
    fn get0(&self, x: i32, y: i32, z: i32) -> Rc<BlockState> {
        let Some(id) = self.world.state_at(x, y, z) else {
            return self.blocked.clone();
        };
        if let Some(state) = self.states.borrow().get(&id) {
            return state.clone();
        }
        let Some(name) = self.world.block_name(id) else {
            return self.blocked.clone();
        };
        let air = matches!(name, "air" | "cave_air" | "void_air");
        let mut boxes = Vec::new();
        self.world.collision_boxes(x, y, z, &mut boxes);
        let full_cube = boxes.iter().any(|b| {
            b.min == [f64::from(x), f64::from(y), f64::from(z)]
                && b.max == [f64::from(x) + 1.0, f64::from(y) + 1.0, f64::from(z) + 1.0]
        });
        let snow_props = (name == "snow").then(|| self.world.block_properties(id));
        if self.world.is_fluid(id) || (!air && !full_cube && snow_props.is_none()) {
            return self.blocked.clone();
        }
        let state = Rc::new(BlockState {
            id,
            name: Arc::from(format!("minecraft:{name}")),
            classes: if air {
                Class::Air.bit()
            } else if snow_props.is_some() {
                Class::SnowLayer.bit()
            } else {
                0
            },
            props: snow_props
                .unwrap_or_default()
                .into_iter()
                .map(|(key, value)| (Arc::from(key), Arc::from(value)))
                .collect::<Vec<_>>()
                .into(),
            fluid: FluidKind::None,
            fluid_amount: 0,
            hardness: -1.0,
            replaceable: air,
            full_collision: full_cube,
            sturdy_top: full_cube,
            pathfindable: air,
        });
        self.states.borrow_mut().insert(id, state.clone());
        state
    }

    fn is_loaded(&self, x: i32, z: i32) -> bool {
        self.world.is_loaded(x, z)
    }
    fn min_y(&self) -> i32 {
        self.world.min_y()
    }
    fn max_y(&self) -> i32 {
        self.world.min_y() + self.world.height() - 1
    }
}

pub fn find_path(
    world: &World,
    start: [f64; 3],
    target: [f64; 3],
    exact_y: bool,
    budget: usize,
) -> Option<Vec<[f64; 3]>> {
    if world.loaded_chunks() == 0 {
        return None;
    }
    let start = start.map(|coordinate| coordinate.floor() as i32);
    let end = target.map(|coordinate| coordinate.floor() as i32);
    let goal = if exact_y {
        rc(GoalBlock(end))
    } else {
        rc(GoalXZ(end[0], end[2]))
    };
    let path = search_path(&LoadedWorld::new(world), start, goal, budget)?;
    let mut points: Vec<_> = path
        .positions
        .iter()
        .skip(1)
        .map(|&[x, y, z]| [f64::from(x) + 0.5, f64::from(y), f64::from(z) + 0.5])
        .collect();
    if let Some(last) = points.last_mut() {
        last[0] = target[0];
        last[2] = target[2];
    } else {
        points.push([target[0], f64::from(start[1]), target[2]]);
    }
    Some(points)
}

pub fn exposed_blocks(
    world: &World,
    filter: &str,
    names: &[String],
    center: [i32; 3],
    radius: i32,
    limit: usize,
) -> Vec<([i32; 3], u32)> {
    let adapter = LoadedWorld::new(world);
    world.find_blocks_where(filter, center, radius, limit, |target, state| {
        world
            .block_name(state)
            .is_some_and(|name| names.iter().any(|wanted| wanted == name))
            && adapter.mining_stands(target).next().is_some()
    })
}

pub fn exposed_stands(world: &World, target: [i32; 3]) -> Vec<[i32; 3]> {
    LoadedWorld::new(world).mining_stands(target).collect()
}

pub fn find_approach(world: &World, start: [f64; 3], stands: Vec<[i32; 3]>) -> Option<[i32; 3]> {
    let adapter = LoadedWorld::new(world);
    let start = start.map(|coordinate| coordinate.floor() as i32);
    let choices: Vec<_> = stands
        .into_iter()
        .filter(|&stand| adapter.standable(stand))
        .map(|stand| rc(GoalBlock(stand)))
        .collect();
    if choices.is_empty() {
        return None;
    }
    search_path(&adapter, start, rc(GoalComposite(choices)), 12_000).map(|path| path.dest())
}

fn search_path(
    adapter: &LoadedWorld,
    start: [i32; 3],
    goal: GoalRef,
    budget: usize,
) -> Option<Path> {
    let settings = Settings::load(|key| match key {
        "allowBreak" | "allowPlace" | "allowParkour" | "allowSwimming" | "allowSprint" => {
            Some(String::from("false"))
        }
        _ => None,
    });
    let facts = PlayerFacts {
        has_throwaway: false,
        can_sprint: false,
        ..PlayerFacts::default()
    };
    let ctx = CalculationContext::new(&settings, adapter, &BareHands, &facts);
    let mut search = AStarPathFinder::new(start, start, goal.clone(), HashMap::new(), 50, true);
    let Status::Done(path) = search.run(&ctx, budget) else {
        return None;
    };
    let path = path.post_process(&ctx);
    if !goal.is_in_goal(path.dest())
        || path
            .positions
            .iter()
            .any(|&[x, _, z]| !adapter.is_loaded(x, z))
    {
        return None;
    }
    Some(path)
}
