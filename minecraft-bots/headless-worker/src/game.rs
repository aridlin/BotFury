use botfury_headless_worker::coordinator::ItemCounts;
use serde::Deserialize;
use serde_json::{Value, json};
use std::{
    io::{BufRead, BufReader, Write},
    process::{Child, ChildStdin, ChildStdout, Command, Stdio},
};

pub struct Game {
    child: Child,
    input: ChildStdin,
    output: BufReader<ChildStdout>,
    sequence: u64,
    pub(crate) baritone: bool,
}

#[derive(Deserialize)]
pub struct Stack {
    pub slot: usize,
    pub item: String,
    pub count: u32,
}

#[derive(Deserialize)]
pub struct Inventory {
    pub slots: Vec<Stack>,
    item_decode_errors: u64,
    cursor: Option<Value>,
}

impl Inventory {
    pub fn counts(&self) -> ItemCounts {
        let mut counts = ItemCounts::new();
        for stack in self
            .slots
            .iter()
            .filter(|slot| (9..45).contains(&slot.slot))
        {
            *counts
                .entry(format!("minecraft:{}", stack.item))
                .or_default() += stack.count;
        }
        counts
    }
}

impl Game {
    pub fn connect(engine: &str, server: &str, name: &str) -> Result<Self, String> {
        let mut child = Command::new(engine)
            .args([
                "mcp",
                "--server",
                server,
                "--name",
                name,
                "--join-timeout",
                "10",
            ])
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .spawn()
            .map_err(|error| error.to_string())?;
        let input = child.stdin.take().expect("piped client input");
        let output = BufReader::new(child.stdout.take().expect("piped client output"));
        let mut game = Self {
            child,
            input,
            output,
            sequence: 0,
            baritone: false,
        };
        game.request("initialize", json!({"protocolVersion":"2024-11-05"}))?;
        game.call("connect", json!({}))?;
        let status = game.call("status", json!({}))?;
        if status["version"] != "26.3 (protocol 777)" || status["gamemode"] != "survival" {
            return Err("Expected a Minecraft 26.3 survival connection".into());
        }
        game.baritone = game.request("tools/list", json!({}))?["tools"]
            .as_array()
            .is_some_and(|tools| tools.iter().any(|tool| tool["name"] == "baritone_approach"));
        Ok(game)
    }

    pub fn dimension(&mut self) -> Result<String, String> {
        let status = self.call("status", json!({}))?;
        if status["in_game"] != true || status["dead"] == true {
            return Err("Worker is unavailable in the game".into());
        }
        status["dimension"]
            .as_str()
            .map(str::to_owned)
            .ok_or("Missing world dimension".into())
    }

    pub fn inventory(&mut self) -> Result<Inventory, String> {
        let inventory = self.inventory_snapshot()?;
        if inventory.cursor.is_some() {
            return Err("The inventory cursor still holds an item".into());
        }
        Ok(inventory)
    }

    pub fn inventory_snapshot(&mut self) -> Result<Inventory, String> {
        let inventory: Inventory = serde_json::from_value(self.call("inventory", json!({}))?)
            .map_err(|error| error.to_string())?;
        if inventory.item_decode_errors != 0 {
            return Err("Client reported an inventory decoding error".into());
        }
        Ok(inventory)
    }

    pub fn call(&mut self, name: &str, arguments: Value) -> Result<Value, String> {
        let result = self.request("tools/call", json!({"name": name, "arguments": arguments}))?;
        let text = result["content"][0]["text"]
            .as_str()
            .ok_or("Missing client response")?;
        if result["isError"] == true {
            return Err(text.into());
        }
        serde_json::from_str(text).map_err(|error| format!("{name}: {error}"))
    }

    fn request(&mut self, method: &str, params: Value) -> Result<Value, String> {
        self.sequence += 1;
        writeln!(
            self.input,
            "{}",
            json!({
                "jsonrpc":"2.0", "id": self.sequence, "method": method, "params": params
            })
        )
        .map_err(|error| error.to_string())?;
        let mut line = String::new();
        self.output
            .read_line(&mut line)
            .map_err(|error| error.to_string())?;
        let message: Value = serde_json::from_str(&line).map_err(|error| error.to_string())?;
        if message["id"] != self.sequence || message.get("error").is_some() {
            return Err(format!("Invalid client response: {message}"));
        }
        Ok(message["result"].clone())
    }
}

impl Drop for Game {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}
