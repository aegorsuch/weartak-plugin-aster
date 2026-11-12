use std::{
    error::Error,
    fs,
    path::Path,
    process::{Command, ExitCode},
    thread,
    time::{Duration, Instant},
};

use redb::{Database, DatabaseError, ReadableDatabase, TableDefinition};

const VALUES: TableDefinition<&str, &str> = TableDefinition::new("probe");

#[cfg(all(unix, not(all(target_os = "android", target_pointer_width = "32"))))]
type OfdFlock = libc::flock;

#[cfg(all(target_os = "android", target_pointer_width = "32"))]
type OfdFlock = libc::flock64;

#[cfg(unix)]
fn ofd_lock(file: &fs::File) -> std::io::Result<bool> {
    use std::os::fd::AsRawFd;
    let mut lock: OfdFlock = unsafe { std::mem::zeroed() };
    lock.l_type = libc::F_WRLCK.try_into().expect("lock type fits c_short");
    lock.l_whence = libc::SEEK_SET.try_into().expect("whence fits c_short");
    // Valid fd and initialized flock; the kernel does not retain the pointer.
    let result = unsafe { libc::fcntl(file.as_raw_fd(), libc::F_OFD_SETLK, &mut lock) };
    if result == 0 {
        return Ok(true);
    }
    let error = std::io::Error::last_os_error();
    match error.raw_os_error() {
        Some(libc::EAGAIN | libc::EACCES) => Ok(false),
        _ => Err(error),
    }
}

#[cfg(unix)]
fn primitive_probe(path: &Path) -> Result<bool, Box<dyn Error>> {
    let path = path.with_extension("ofd");
    let first = fs::OpenOptions::new()
        .read(true)
        .write(true)
        .create_new(true)
        .open(&path)?;
    let second = fs::OpenOptions::new().read(true).write(true).open(&path)?;
    if !ofd_lock(&first)? {
        return Err("first OFD lock unexpectedly refused".into());
    }
    let same = !ofd_lock(&second)?;
    let child = Command::new(std::env::current_exe()?)
        .arg("ofd-check")
        .arg(&path)
        .status()?;
    drop(first);
    let release = ofd_lock(&second)?;
    println!("OFD_SAME_PROCESS_REFUSED={same} OFD_RELEASED_ON_CLOSE={release}");
    drop(second);
    let mut holder = Command::new(std::env::current_exe()?)
        .arg("ofd-hold")
        .arg(&path)
        .spawn()?;
    let deadline = Instant::now() + Duration::from_secs(30);
    while !path.with_extension("ofd-ready").exists() {
        if holder.try_wait()?.is_some() {
            return Err("OFD holder exited before readiness".into());
        }
        if Instant::now() >= deadline {
            holder.kill()?;
            holder.wait()?;
            return Err("OFD holder readiness timed out".into());
        }
        thread::sleep(Duration::from_millis(50));
    }
    let competitor = fs::OpenOptions::new().read(true).write(true).open(&path)?;
    let blocked = ofd_lock(&competitor);
    holder.kill()?;
    holder.wait()?;
    let held = !blocked?;
    let released = ofd_lock(&competitor)?;
    println!("OFD_HOLDER_REFUSED={held} OFD_RELEASED_AFTER_KILL={released}");
    Ok(same && child.success() && release && held && released)
}

fn refused(path: &Path) -> Result<bool, Box<dyn Error>> {
    match Database::create(path) {
        Err(DatabaseError::DatabaseAlreadyOpen) => Ok(true),
        Err(error) => Err(error.into()),
        Ok(database) => {
            drop(database);
            Ok(false)
        }
    }
}

fn run() -> Result<bool, Box<dyn Error>> {
    let mut args = std::env::args_os().skip(1);
    let mode = args.next().ok_or("expected probe or check")?;
    let path = args.next().ok_or("expected database path")?;
    if args.next().is_some() {
        return Err("unexpected argument".into());
    }
    let path = Path::new(&path);
    #[cfg(unix)]
    if mode == "ofd-check" {
        let file = fs::OpenOptions::new().read(true).write(true).open(path)?;
        let blocked = !ofd_lock(&file)?;
        println!("OFD_CROSS_PROCESS_REFUSED={blocked}");
        return Ok(blocked);
    }
    #[cfg(unix)]
    if mode == "ofd-hold" {
        let file = fs::OpenOptions::new().read(true).write(true).open(path)?;
        if !ofd_lock(&file)? {
            return Err("OFD holder could not acquire lock".into());
        }
        fs::write(path.with_extension("ofd-ready"), b"ready")?;
        loop {
            thread::sleep(Duration::from_secs(1));
        }
    }
    if mode == "hold" {
        let _database = Database::open(path)?;
        fs::write(path.with_extension("ready"), b"ready")?;
        loop {
            thread::sleep(Duration::from_secs(1));
        }
    }
    if mode == "dirty-hold" {
        let database = Database::open(path)?;
        let transaction = database.begin_write()?;
        {
            let mut table = transaction.open_table(VALUES)?;
            table.insert("event", "uncommitted-overwrite")?;
            table.insert("uncommitted", "must-not-survive")?;
        }
        fs::write(path.with_extension("dirty-ready"), b"ready")?;
        loop {
            thread::sleep(Duration::from_secs(1));
        }
    }
    if mode == "check" {
        let blocked = refused(path)?;
        println!("CROSS_PROCESS_REFUSED={blocked}");
        return Ok(blocked);
    }
    if mode != "probe" {
        return Err("unknown mode".into());
    }
    if path.exists() {
        return Err("probe requires a new database path".into());
    }
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let database = Database::create(path)?;
    let transaction = database.begin_write()?;
    {
        let mut table = transaction.open_table(VALUES)?;
        table.insert("event", "durable-value")?;
    }
    transaction.commit()?;
    let same = refused(path)?;
    println!("SAME_PROCESS_REFUSED={same}");
    let child = Command::new(std::env::current_exe()?)
        .arg("check")
        .arg(path)
        .status()?;
    let cross = child.success();
    drop(database);
    let database = Database::open(path)?;
    let transaction = database.begin_read()?;
    let table = transaction.open_table(VALUES)?;
    let retained = table
        .get("event")?
        .is_some_and(|value| value.value() == "durable-value");
    println!("REOPEN_VALUE_RETAINED={retained}");
    drop(table);
    drop(transaction);
    drop(database);
    let mut holder = Command::new(std::env::current_exe()?)
        .arg("hold")
        .arg(path)
        .spawn()?;
    let deadline = Instant::now() + Duration::from_secs(30);
    while !path.with_extension("ready").exists() {
        if holder.try_wait()?.is_some() {
            return Err("holder exited before readiness".into());
        }
        if Instant::now() >= deadline {
            holder.kill()?;
            holder.wait()?;
            return Err("holder readiness timed out".into());
        }
        thread::sleep(Duration::from_millis(50));
    }
    let live_holder = refused(path)?;
    holder.kill()?;
    holder.wait()?;
    let database = Database::open(path)?;
    let transaction = database.begin_read()?;
    let table = transaction.open_table(VALUES)?;
    let after_kill = table
        .get("event")?
        .is_some_and(|value| value.value() == "durable-value");
    println!("HOLDER_REFUSED={live_holder} REOPEN_AFTER_KILL_VALUE_RETAINED={after_kill}");
    drop(table);
    drop(transaction);
    drop(database);
    let mut dirty = Command::new(std::env::current_exe()?)
        .arg("dirty-hold")
        .arg(path)
        .spawn()?;
    let deadline = Instant::now() + Duration::from_secs(30);
    while !path.with_extension("dirty-ready").exists() {
        if dirty.try_wait()?.is_some() {
            return Err("dirty writer exited before readiness".into());
        }
        if Instant::now() >= deadline {
            dirty.kill()?;
            dirty.wait()?;
            return Err("dirty writer readiness timed out".into());
        }
        thread::sleep(Duration::from_millis(50));
    }
    let dirty_refused = refused(path);
    dirty.kill()?;
    dirty.wait()?;
    let dirty_refused = dirty_refused?;
    let database = Database::open(path)?;
    let transaction = database.begin_read()?;
    let table = transaction.open_table(VALUES)?;
    let rollback = table
        .get("event")?
        .is_some_and(|value| value.value() == "durable-value")
        && table.get("uncommitted")?.is_none();
    println!("DIRTY_WRITER_REFUSED={dirty_refused} UNCOMMITTED_WRITE_ROLLED_BACK={rollback}");
    #[cfg(unix)]
    let primitive = primitive_probe(path)?;
    #[cfg(not(unix))]
    let primitive = true;
    Ok(same
        && cross
        && retained
        && live_holder
        && after_kill
        && dirty_refused
        && rollback
        && primitive)
}

fn main() -> ExitCode {
    match run() {
        Ok(true) => ExitCode::SUCCESS,
        Ok(false) => ExitCode::FAILURE,
        Err(error) => {
            eprintln!("ERROR: {error}");
            ExitCode::FAILURE
        }
    }
}
