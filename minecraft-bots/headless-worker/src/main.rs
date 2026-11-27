mod game;
mod jobs;

use botfury_headless_worker::coordinator::{Coordinator, Finished, Kind};
use game::Game;
use std::{env, process::ExitCode, thread, time::Duration};

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("{error}");
            ExitCode::FAILURE
        }
    }
}

fn run() -> Result<(), String> {
    let [engine, server, name, port, project, count]: [String; 6] = env::args()
        .skip(1).collect::<Vec<_>>().try_into()
        .map_err(|_| "Usage: botfury-headless-worker ENGINE SERVER:PORT NAME COORDINATOR_PORT PROJECT JOB_COUNT")?;
    let port = port.parse().map_err(|_| "Invalid coordinator port")?;
    let count = count.parse::<usize>().map_err(|_| "Invalid job count")?;
    if count == 0 || project.is_empty() {
        return Err("Specify a project and a positive job count".into());
    }
    let (host, server_port) = server.rsplit_once(':').ok_or("Specify SERVER:PORT")?;
    let server_port = server_port
        .parse::<u16>()
        .map_err(|_| "Invalid Minecraft server port")?;
    let host = match host.to_ascii_lowercase().as_str() {
        "127.0.0.1" | "localhost" | "[::1]" => "local".into(),
        value => value.to_owned(),
    };
    let coordinator = Coordinator::new(port, name.clone());
    let mut game = Game::connect(&engine, &server, &name)?;
    let kinds = [Kind::Gather, Kind::Craft, Kind::Transfer];
    for _ in 0..count {
        let items = game.inventory()?.counts();
        let assignment = (0..20)
            .find_map(
                |_| match coordinator.claim(Some(&project), &kinds, &items) {
                    Ok(None) => {
                        thread::sleep(Duration::from_millis(500));
                        None
                    }
                    result => Some(result),
                },
            )
            .transpose()
            .map_err(|error| format!("Claim failed: {error:?}"))?
            .flatten()
            .ok_or("No job available")?;
        let world = format!("{host}:{server_port}|{}", game.dimension()?);
        if assignment.world != world {
            return Err(format!(
                "Job belongs to {} while worker is in {world}",
                assignment.world
            ));
        }
        let outcome = jobs::execute(&coordinator, &assignment, &mut game)?;
        match coordinator
            .finish(&assignment, true, &outcome.evidence, outcome.chest.as_ref())
            .map_err(|error| format!("Finish failed: {error:?}"))?
        {
            Finished::Accepted => {
                println!("Completed {}: {}", assignment.job.work.id, outcome.evidence)
            }
            _ => return Err("The coordinator did not confirm completion".into()),
        }
    }
    Ok(())
}
