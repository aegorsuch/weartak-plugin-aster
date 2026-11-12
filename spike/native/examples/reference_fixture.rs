// SPDX-License-Identifier: GPL-3.0-or-later
use aster_mesh::{ProvisioningAccess, ReferenceProvisioner, Scope, Topic};
use aster_node::mission::UnprotectedReferenceMission;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args_os().skip(1);
    let path = args.next().ok_or("expected new reference bundle path")?;
    let node = args
        .next()
        .map(|value| value.to_string_lossy().parse::<u64>())
        .transpose()?
        .unwrap_or(1);
    let authority_seed = args
        .next()
        .map(|value| value.to_string_lossy().parse::<u8>())
        .transpose()?
        .unwrap_or(0xb7);
    if args.next().is_some() {
        return Err("expected path, optional node number, optional authority seed byte".into());
    }
    // Public deterministic evaluation seed; never operational provisioning.
    let mut provisioner = ReferenceProvisioner::from_seed([authority_seed; 32])?;
    let access = ProvisioningAccess::member(
        Scope::new("weartak/spike")?,
        vec![1],
        vec![Topic::new("jni-evaluation")?],
    )?;
    let bytes = provisioner.issue_node(node, &[access])?.to_bytes()?;
    UnprotectedReferenceMission::persist(path, bytes)?;
    println!("DISPOSABLE_REFERENCE_FIXTURE_CREATED");
    Ok(())
}
