// File: Storage Manager
//
//    Store and retrieve sensitive data.  Data is stored in the Application Support directory of the app, encrypted in
// place via the .completeFileProtection option, and marked as excluded from backups so it will not be included in
// iCloud backps.

//
// Imports
//

import Foundation

//
// Code
//

// Class: StorageManager
//    Store and retrieve sensitive data.

class StorageManager: NSObject {
   // Local-Method: path()
   //    Get the path to the application support dir, appending the given file name to it.  We use the application
   // support directory because its contents are not shared.
   //
   // Arguments:
   //    file - the name of the file
   //
   // Returns:
   //    An URL for the named file in the app's Application Support directory.

   private func path(file: String) -> URL? {
      do {
         //    Get the applications support dir, and tack the name of the thing we're storing on the end of it.
         // This does imply that `file` should be a valid filename.

         let asdir = try FileManager.default.url(for: .applicationSupportDirectory,
                                                 in: .userDomainMask,
                                                 appropriateFor: nil,  // Ignored
                                                 create: true) // May not exist, make if necessary.

         return asdir.appendingPathComponent(file)
      } catch { // Did the attempt to get the application support dir fail?
         print("Failed to get/create the application support dir.")
         return nil
      }
   }

   // Method: add()
   //    Store a value for a specified key, encrypted in place.
   //
   // Arguments:
   //     key   - the name of the file
   //     value - the data to store
   //
   // Returns:
   //    A boolean indicating success.

   func add(key: String, value: Data) -> Bool {
      guard let file = path(file: key) else { return false }

      do {
         try value.write(to: file, options: .completeFileProtection)
      } catch {
         print("Failed to write the data for '\(key)'.")
         return false
      }

      return true
   }

   // Method: get()
   //    Get a value for the specified key.
   //
   // Arguments:
   //    key - the name associated with the data
   //
   // Returns:
   //    Optional data potentially containing the value associated with the key; may be `nil`.

   func get(key: String) -> Data? {
      guard let file = path(file: key) else { return nil }

      do {
         let data = try Data(contentsOf: file)
         return data
      } catch {
         print("Failed to read '\(file)'.")
      }

      return nil
   }

   // Method: remove()
   //    Remove a key/value pair. Removing a nonexistent key/value pair is not an error.
   //
   // Arguments:
   //    key - the name of the file
   //
   // Returns:
   //    A boolean indicating success; at present, there is no failure path, but this may change in the future.

   func remove(key: String) -> Bool {
      guard let file = path(file: key) else { return true }

      do {
         try FileManager.default.removeItem(at: file)
      } catch {
         // It's fine if the file isn't there.
      }

      return true
   }

   // Method: sys_test()
   //    Check to see if everything works.

   func sys_test() {
      let key   = "test_key"
      let value = Data("Some random string of text. 😎".utf8)

      if !add(key: key, value: value) {
         print("\(self.classForCoder):\(#function): Failed add() key/value pair.")
         return
      }

      guard let payload = get(key: key) else {
         print("\(self.classForCoder):\(#function): Failed get() value for key.")
         return
      }

      if !(payload == value) {
         print("\(self.classForCoder):\(#function): Mismatch between stored & retrieved value.")
         return
      }

      if !remove(key: key) {
         print("\(self.classForCoder):\(#function): Failed to delete key/value pair.")
         return
      }

      print("\(self.classForCoder):\(#function): Completed successfully.")
   }
}

//
// Copyright © 2024, Spruce Systems, Inc.
//
