import 'package:flutter/material.dart';

import 'demos/credential_pack_demo.dart';
import 'demos/oid4vci_demo.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'SpruceKit Mobile Examples',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.green),
        useMaterial3: true,
      ),
      home: const HomeScreen(),
    );
  }
}

class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final demos = [
      ('OID4VCI Issuance', Icons.badge, const Oid4vciDemo()),
      ('Credential Pack', Icons.folder, const CredentialPackDemo()),
    ];

    return Scaffold(
      appBar: AppBar(title: const Text('SpruceKit Mobile Examples')),
      body: ListView.separated(
        itemCount: demos.length,
        separatorBuilder: (_, _) => const Divider(height: 1),
        itemBuilder: (context, index) {
          final (title, icon, screen) = demos[index];
          return ListTile(
            leading: Icon(icon),
            title: Text(title),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => Navigator.push(
              context,
              MaterialPageRoute(builder: (_) => screen),
            ),
          );
        },
      ),
    );
  }
}
