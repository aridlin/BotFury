//! Worker-side client for the existing BotFury project broker.

use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::BTreeMap;
use std::{thread, time::Duration};
use ureq::{Agent, http::Response};

#[derive(Debug)]
pub enum BrokerError {
    Request(ureq::Error),
    Rejected(u16),
    InvalidResponse(&'static str),
}

impl From<ureq::Error> for BrokerError {
    fn from(error: ureq::Error) -> Self {
        Self::Request(error)
    }
}

impl BrokerError {
    fn transient(&self) -> bool {
        matches!(
            self,
            Self::Request(
                ureq::Error::Timeout(_) | ureq::Error::Io(_) | ureq::Error::ConnectionFailed
            ) | Self::Rejected(502..=504)
        )
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Kind {
    Gather,
    Craft,
    Transfer,
}

#[derive(Debug, Deserialize)]
pub struct Work {
    pub id: String,
    pub kind: Kind,
    pub payload: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Lease {
    pub work: Work,
    pub worker: String,
    pub lease: String,
    pub expires_at: u64,
}

#[derive(Debug, Deserialize)]
pub struct Assignment {
    pub project: String,
    pub world: String,
    pub job: Lease,
}

pub type ItemCounts = BTreeMap<String, u32>;

#[derive(Serialize)]
pub struct ChestReport {
    pub location: String,
    pub items: ItemCounts,
    pub free: Option<u16>,
}

pub enum Renewed {
    Active(Lease),
    Lost,
}

pub enum Finished {
    Accepted,
    Continued(Assignment),
    AwaitingReceipt(Lease),
    Lost,
}

pub struct Coordinator {
    http: Agent,
    url: String,
    worker: String,
}

impl Coordinator {
    /// A remote master can be reached through a private tunnel terminating on this loopback port.
    pub fn new(port: u16, worker: String) -> Self {
        let http = Agent::config_builder()
            .timeout_global(Some(Duration::from_secs(2)))
            .max_redirects(0)
            .proxy(None)
            .http_status_as_error(false)
            .build()
            .new_agent();
        Self {
            http,
            url: format!("http://127.0.0.1:{port}/projects"),
            worker,
        }
    }

    pub fn claim(
        &self,
        project: Option<&str>,
        kinds: &[Kind],
        items: &ItemCounts,
    ) -> Result<Option<Assignment>, BrokerError> {
        let request = serde_json::json!({
            "action": "claim",
            "project": project,
            "worker": self.worker,
            "kinds": kinds,
            "claimItems": items
        });
        retry_transient(|| self.read_claim(project, kinds, &request))
    }

    fn read_claim(
        &self,
        project: Option<&str>,
        kinds: &[Kind],
        request: &Value,
    ) -> Result<Option<Assignment>, BrokerError> {
        let response = self.send(request)?;
        match response.status().as_u16() {
            200 => {
                let claimed: Option<Assignment> = decode(response)?;
                if let Some(assignment) = &claimed
                    && (!self.worker.eq_ignore_ascii_case(&assignment.job.worker)
                        || !kinds.contains(&assignment.job.work.kind)
                        || project.is_some_and(|id| id != assignment.project))
                {
                    return Err(BrokerError::InvalidResponse("unexpected assignment"));
                }
                Ok(claimed)
            }
            code => Err(BrokerError::Rejected(code)),
        }
    }

    pub fn renew(&self, assignment: &Assignment) -> Result<Renewed, BrokerError> {
        let request = serde_json::json!({
            "action": "renew",
            "project": assignment.project,
            "worker": self.worker,
            "job": assignment.job.work.id,
            "token": assignment.job.lease
        });
        retry_transient(|| self.read_renewal(assignment, &request))
    }

    fn read_renewal(
        &self,
        assignment: &Assignment,
        request: &Value,
    ) -> Result<Renewed, BrokerError> {
        let response = self.send(request)?;
        match response.status().as_u16() {
            200 => self
                .check_lease(assignment, decode(response)?)
                .map(Renewed::Active),
            409 => Ok(Renewed::Lost),
            code => Err(BrokerError::Rejected(code)),
        }
    }

    pub fn finish(
        &self,
        assignment: &Assignment,
        success: bool,
        evidence: &str,
        chest: Option<&ChestReport>,
    ) -> Result<Finished, BrokerError> {
        let response = self.send(&serde_json::json!({
            "action": "finish",
            "project": assignment.project,
            "worker": self.worker,
            "job": assignment.job.work.id,
            "token": assignment.job.lease,
            "success": success,
            "evidence": evidence,
            "chest": chest
        }))?;
        match response.status().as_u16() {
            200 => {
                let result: Value = decode(response)?;
                if result == Value::Bool(true) {
                    return Ok(Finished::Accepted);
                }
                if !result.is_object() || !success {
                    return Err(BrokerError::InvalidResponse("invalid completion receipt"));
                }
                let next: Assignment = serde_json::from_value(result)
                    .map_err(|_| BrokerError::InvalidResponse("invalid continuation"))?;
                if next.project != assignment.project
                    || next.world != assignment.world
                    || !next.job.worker.eq_ignore_ascii_case(&self.worker)
                {
                    return Err(BrokerError::InvalidResponse("unexpected continuation"));
                }
                Ok(Finished::Continued(next))
            }
            202 => self
                .check_lease(assignment, decode(response)?)
                .map(Finished::AwaitingReceipt),
            409 => Ok(Finished::Lost),
            code => Err(BrokerError::Rejected(code)),
        }
    }

    fn check_lease(&self, assignment: &Assignment, lease: Lease) -> Result<Lease, BrokerError> {
        if !self.worker.eq_ignore_ascii_case(&lease.worker)
            || lease.lease != assignment.job.lease
            || lease.work.id != assignment.job.work.id
            || lease.work.kind != assignment.job.work.kind
        {
            return Err(BrokerError::InvalidResponse("lease changed during renewal"));
        }
        Ok(lease)
    }

    fn send(&self, payload: &Value) -> Result<Response<ureq::Body>, BrokerError> {
        Ok(self.http.post(&self.url).send_json(payload)?)
    }
}

fn retry_transient<T>(mut send: impl FnMut() -> Result<T, BrokerError>) -> Result<T, BrokerError> {
    // The broker returns the live lease for the same worker and renews only a matching token.
    for delay in [150, 350] {
        match send() {
            Err(error) if error.transient() => thread::sleep(Duration::from_millis(delay)),
            result => return result,
        }
    }
    send()
}

fn decode<T: serde::de::DeserializeOwned>(
    response: Response<ureq::Body>,
) -> Result<T, BrokerError> {
    Ok(response
        .into_body()
        .with_config()
        .limit(1024 * 1024)
        .read_json()?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn retry_stops_on_a_definitive_rejection() {
        let mut attempts = 0;
        let result = retry_transient(|| {
            attempts += 1;
            Err::<(), _>(BrokerError::Rejected(if attempts == 1 { 503 } else { 409 }))
        });
        assert!(matches!(result, Err(BrokerError::Rejected(409))));
        assert_eq!(attempts, 2);
    }
}
