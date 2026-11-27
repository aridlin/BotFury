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
    if project.is_empty() {
        return Err("Specify a project".into());
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
    let kinds = [
        Kind::Gather,
        Kind::Craft,
        Kind::Transfer,
        Kind::Build,
        Kind::Verify,
    ];
    let mut completed = 0;
    while count == 0 || completed < count {
        let items = game.inventory()?.counts();
        let assignment = (0..24)
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
            .flatten();
        let Some(assignment) = assignment else {
            return if count == 0 {
                Ok(())
            } else {
                Err("No job available".into())
            };
        };
        let world = format!("{host}:{server_port}|{}", game.dimension()?);
        if assignment.world != world {
            return Err(format!(
                "Job belongs to {} while worker is in {world}",
                assignment.world
            ));
        }
        let outcome = jobs::execute(&coordinator, &assignment, &mut game)?;
        match coordinator
            .finish(
                &assignment,
                outcome.success,
                &outcome.evidence,
                outcome.chest.as_ref(),
            )
            .map_err(|error| format!("Finish failed: {error:?}"))?
        {
            Finished::Accepted if outcome.success => {
                completed += 1;
                println!("Completed {}: {}", assignment.job.work.id, outcome.evidence)
            }
            Finished::Accepted => {
                println!("Released {}: {}", assignment.job.work.id, outcome.evidence)
            }
            _ => return Err("The coordinator did not confirm completion".into()),
        }
    }
    Ok(())
}
