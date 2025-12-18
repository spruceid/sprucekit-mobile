import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:sprucekit_mobile/sprucekit_mobile.dart';

/// Decode a potentially base64-encoded JSONPath to readable field name
String decodeFieldPath(String path) {
  try {
    final decoded = utf8.decode(base64Decode(path));
    // Extract field name from JSONPath like $['@context'] -> @context
    final match = RegExp(r"\['([^']+)'\]").firstMatch(decoded);
    if (match != null) {
      return match.group(1)!;
    }
    return decoded;
  } catch (_) {
    // If decoding fails, return original path
    return path;
  }
}

/// A widget that displays a list of fields for selective disclosure.
///
/// This widget allows users to select which fields to share from a credential.
/// Required fields are pre-selected and cannot be deselected.
///
/// Example:
/// ```dart
/// SelectiveDisclosureFields(
///   fields: requestedFields,
///   selectedPaths: selectedFields,
///   isSelectiveDisclosable: credential.selectiveDisclosable,
///   onFieldToggled: (path, isSelected) {
///     setState(() {
///       if (isSelected) {
///         selectedFields.add(path);
///       } else {
///         selectedFields.remove(path);
///       }
///     });
///   },
/// )
/// ```
class SelectiveDisclosureFields extends StatelessWidget {
  /// Creates a selective disclosure fields widget.
  const SelectiveDisclosureFields({
    super.key,
    required this.fields,
    required this.selectedPaths,
    required this.onFieldToggled,
    this.isSelectiveDisclosable = true,
    this.title,
    this.showTitle = true,
  });

  /// The list of fields to display.
  final List<RequestedFieldData> fields;

  /// The set of currently selected field paths.
  final Set<String> selectedPaths;

  /// Whether the credential supports selective disclosure.
  /// If false, all fields are shown but cannot be toggled.
  final bool isSelectiveDisclosable;

  /// Callback when a field's selection state changes.
  final void Function(String path, bool isSelected) onFieldToggled;

  /// Optional custom title. Defaults to 'Select fields to share:'.
  final String? title;

  /// Whether to show the title. Defaults to true.
  final bool showTitle;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (showTitle) ...[
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            child: Text(
              title ?? 'Select fields to share:',
              style: Theme.of(context).textTheme.titleMedium,
            ),
          ),
        ],
        ...fields.map((field) => _buildFieldTile(context, field)),
      ],
    );
  }

  Widget _buildFieldTile(BuildContext context, RequestedFieldData field) {
    final isSelected = selectedPaths.contains(field.path) || field.required;
    final canToggle = isSelectiveDisclosable && !field.required;

    // Decode field name for display
    final displayName = decodeFieldPath(field.name ?? field.path);

    return CheckboxListTile(
      value: isSelected,
      onChanged: canToggle
          ? (value) {
              onFieldToggled(field.path, value ?? false);
            }
          : null,
      title: Text(displayName),
      subtitle: field.purpose != null ? Text(field.purpose!) : null,
      secondary: field.required ? const Chip(label: Text('Required')) : null,
    );
  }
}

/// A simplified version that wraps fields in a scrollable list.
class SelectiveDisclosureFieldsList extends StatelessWidget {
  const SelectiveDisclosureFieldsList({
    super.key,
    required this.fields,
    required this.selectedPaths,
    required this.onFieldToggled,
    this.isSelectiveDisclosable = true,
    this.title,
    this.padding = const EdgeInsets.all(16),
  });

  final List<RequestedFieldData> fields;
  final Set<String> selectedPaths;
  final bool isSelectiveDisclosable;
  final void Function(String path, bool isSelected) onFieldToggled;
  final String? title;
  final EdgeInsets padding;

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: padding,
      children: [
        SelectiveDisclosureFields(
          fields: fields,
          selectedPaths: selectedPaths,
          isSelectiveDisclosable: isSelectiveDisclosable,
          onFieldToggled: onFieldToggled,
          title: title,
        ),
      ],
    );
  }
}
