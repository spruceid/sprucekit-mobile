/// Storage Manager
///
/// Store and retrieve sensitive data.  Data is stored in the Application Support directory of the app, encrypted in
/// place via the .completeFileProtection option, and marked as excluded from backups so it will not be included in
/// iCloud backps.

import Foundation

import SpruceIDMobileSdkRs

// The following is a stripped-down version of the protocol definition from the Rust layer against which the storage
// manager is intended to link.

/// Store and retrieve sensitive data.
public class StorageManager: NSObject, StorageManagerInterface {
    let appGroupId: String?

    /// - Parameters:
    ///   - appGroupId: The app group under whose directory the StorageManager will place its data store.
    ///                 Defaults to the app's directory. See the com.apple.security.application-groups entitlement.
    public init(appGroupId: String?) {
        self.appGroupId = appGroupId
    }

    /// Migrate the data store from the app's directory to the provided app group.
    public func migrationToAppGroupFileManager() throws {
        if appGroupId == nil {
            throw StorageManagerError.InternalError
        }
        let fileman = FileManager.default
        var bundle = Bundle.main
        guard let appGroupAsdir = fileman.containerURL(forSecurityApplicationGroupIdentifier: appGroupId!)
        else {
            throw StorageManagerError.InternalError
        }
        let appAsdir = try fileman.url(for: .applicationSupportDirectory,
                                       in: .userDomainMask,
                                       appropriateFor: nil, // Ignored
                                       create: false)
        guard let appname = bundle.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String ??
            bundle.object(forInfoDictionaryKey: "CFBundleName") as? String
        else {
            throw StorageManagerError.InternalError
        }
        let appGroupDatadir: URL
        let appDatadir: URL
        if #available(iOS 16.0, *) {
            appGroupDatadir = appGroupAsdir.appending(path: "\(appname)/sprucekit/datastore/",
                                                      directoryHint: .isDirectory)
            appDatadir = appAsdir.appending(path: "\(appname)/sprucekit/datastore/", directoryHint: .isDirectory)
        } else {
            appGroupDatadir = appGroupAsdir.appendingPathComponent("\(appname)/sprucekit/datastore/")
            appDatadir = appAsdir.appendingPathComponent("\(appname)/sprucekit/datastore/")
        }
        if fileman.fileExists(atPath: appDatadir.path) {
            try fileman.createDirectory(at: appGroupDatadir.deletingLastPathComponent(),
                                        withIntermediateDirectories: true,
                                        attributes: nil)
            try fileman.moveItem(at: appDatadir, to: appGroupDatadir)
        }
    }

    /// Get the path to the application support dir, appending the given file name to it.
    ///
    /// We use the application support directory because its contents are not shared.
    ///
    /// - Parameters:
    ///    - file: the name of the file
    ///
    /// - Returns: An URL for the named file in the app's Application Support directory.
    private func path(file: String) async -> URL? {
        do {
            //    Get the applications support dir, and tack the name of the thing we're storing on the end of it.
            // This does imply that `file` should be a valid filename.

            let fileman = FileManager.default
            var bundle = Bundle.main

            // getting the bundle of the main app if we are in an app extension
            if bundle.bundleURL.pathExtension == "appex" {
                // Peel off two directory levels - MY_APP.app/PlugIns/MY_APP_EXTENSION.appex
                let url = bundle.bundleURL.deletingLastPathComponent().deletingLastPathComponent()
                if let otherBundle = Bundle(url: url) {
                    bundle = otherBundle
                }
            }

            guard let asdir = if let id = appGroupId {
                fileman.containerURL(forSecurityApplicationGroupIdentifier: id)
            } else {
                try fileman.url(for: .applicationSupportDirectory,
                                in: .userDomainMask,
                                appropriateFor: nil, // Ignored
                                create: true) // May not exist, make if necessary.
            } else {
                return nil
            }

            //    If we create subdirectories in the application support directory, we need to put them in a subdir
            // named after the app; normally, that's `CFBundleDisplayName` from `info.plist`, but that key doesn't
            // have to be set, in which case we need to use `CFBundleName`.

            guard let appname = bundle.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String ??
                bundle.object(forInfoDictionaryKey: "CFBundleName") as? String
            else {
                return nil
            }

            let datadir: URL

            if #available(iOS 16.0, *) {
                datadir = asdir.appending(path: "\(appname)/sprucekit/datastore/", directoryHint: .isDirectory)
            } else {
                datadir = asdir.appendingPathComponent("\(appname)/sprucekit/datastore/")
            }

            if !fileman.fileExists(atPath: datadir.path) {
                try fileman.createDirectory(at: datadir, withIntermediateDirectories: true, attributes: nil)
            }

            return datadir.appendingPathComponent(file)
        } catch {
            return nil
        }
    }

    /// Store a value for a specified key, encrypted in place.
    ///
    /// - Parameters:
    ///     - key:   the name of the file
    ///     - value: the data to store
    ///
    /// - Returns: a boolean indicating success

    public func add(key: Key, value: Value) async throws {
        guard let file = await path(file: key) else { throw StorageManagerError.InternalError }

        do {
            try value.write(to: file, options: .completeFileProtection)
        } catch {
            throw StorageManagerError.InternalError
        }
    }

    /// Get a value for the specified key.
    ///
    /// - Parameters:
    ///    - key: the name associated with the data
    ///
    /// - Returns: optional data potentially containing the value associated with the key; may be `nil`

    public func get(key: Key) async throws -> Value? {
        guard let file = await path(file: key) else { throw StorageManagerError.InternalError }

        do {
            return try Data(contentsOf: file)
        } catch let error as CocoaError {
            switch error.code.rawValue {
            // File not found:
            case 260:
                return nil
            default:
                print("uncaught error \(error.localizedDescription)")
                throw StorageManagerError.InternalError
            }
        } catch {
            print("uncaught error \(error.localizedDescription)")
            throw StorageManagerError.InternalError
        }
    }

    /// List the the items in storage.
    ///
    /// Note that this will list all items in the `application support` directory, potentially including any files
    /// created by other systems.
    ///
    /// - Returns: a list of items in storage

    public func list() async throws -> [Key] {
        guard let asdir = await path(file: "")?.path else { return [String]() }

        do {
            return try FileManager.default.contentsOfDirectory(atPath: asdir)
        } catch {
            throw StorageManagerError.InternalError
        }
    }

    /// Remove a key/value pair.
    ///
    /// Removing a nonexistent key/value pair is not an error.
    ///
    /// - Parameters:
    ///    - key: the name of the file
    ///
    /// - Returns: a boolean indicating success; at present, there is no failure path, but this may change

    public func remove(key: Key) async throws {
        guard let file = await path(file: key) else { return }

        do {
            try FileManager.default.removeItem(at: file)
        } catch {
            // It's fine if the file isn't there.
        }
    }
}

//
// Copyright © 2024, Spruce Systems, Inc.
//
