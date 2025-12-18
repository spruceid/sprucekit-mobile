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

/// Generic field data for selective disclosure UI.
///
/// This class abstracts the differences between W3C VC fields (RequestedFieldData)
/// and mDoc fields (RequestedField180137Data) to allow reuse of the UI widget.
class SelectiveDisclosureFieldData {
  const SelectiveDisclosureFieldData({
    required this.id,
    required this.displayName,
    this.displayValue,
    this.purpose,
    this.required = false,
    this.selectivelyDisclosable = true,
    this.intentToRetain = false,
  });

  /// Unique identifier for the field (used for selection tracking)
  final String id;

  /// Human-readable name of the field
  final String displayName;

  /// Human-readable value of the field (if available)
  final String? displayValue;

  /// Purpose for requesting this field
  final String? purpose;

  /// Whether the field is required
  final bool required;

  /// Whether the field can be selectively disclosed
  final bool selectivelyDisclosable;

  /// Whether the verifier intends to retain this data
  final bool intentToRetain;

  /// Create from W3C VC RequestedFieldData
  factory SelectiveDisclosureFieldData.fromRequestedField(
    RequestedFieldData field,
  ) {
    return SelectiveDisclosureFieldData(
      id: field.path, // Use path as ID for W3C VC
      displayName: decodeFieldPath(field.name ?? field.path),
      purpose: field.purpose,
      required: field.required,
      selectivelyDisclosable:
          true, // W3C VC always supports selective disclosure
      intentToRetain: field.retained,
    );
  }

  /// Create from mDoc RequestedField180137Data
  factory SelectiveDisclosureFieldData.fromMdocField(
    RequestedField180137Data field,
  ) {
    return SelectiveDisclosureFieldData(
      id: field.id,
      displayName: field.displayableName,
      displayValue: field.displayableValue,
      purpose: field.purpose,
      required: field.required,
      selectivelyDisclosable: field.selectivelyDisclosable,
      intentToRetain: field.intentToRetain,
    );
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
///   fields: requestedFields.map(SelectiveDisclosureFieldData.fromRequestedField).toList(),
///   selectedIds: selectedFields,
///   onFieldToggled: (id, isSelected) {
///     setState(() {
///       if (isSelected) {
///         selectedFields.add(id);
///       } else {
///         selectedFields.remove(id);
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
    required this.selectedIds,
    required this.onFieldToggled,
    this.title,
    this.showTitle = true,
  });

  /// The list of fields to display.
  final List<SelectiveDisclosureFieldData> fields;

  /// The set of currently selected field IDs.
  final Set<String> selectedIds;

  /// Callback when a field's selection state changes.
  final void Function(String id, bool isSelected) onFieldToggled;

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

  Widget _buildFieldTile(
    BuildContext context,
    SelectiveDisclosureFieldData field,
  ) {
    final isSelected = selectedIds.contains(field.id) || field.required;
    final canToggle = field.selectivelyDisclosable && !field.required;

    return CheckboxListTile(
      value: isSelected,
      onChanged: canToggle
          ? (value) {
              onFieldToggled(field.id, value ?? false);
            }
          : null,
      title: Text(field.displayName),
      subtitle: _buildSubtitle(field),
      secondary: field.required ? const Chip(label: Text('Required')) : null,
    );
  }

  Widget? _buildSubtitle(SelectiveDisclosureFieldData field) {
    final parts = <Widget>[];

    if (field.displayValue != null) {
      parts.add(Text(field.displayValue!));
    }

    if (field.purpose != null) {
      parts.add(
        Text(
          field.purpose!,
          style: const TextStyle(fontStyle: FontStyle.italic),
        ),
      );
    }

    if (field.intentToRetain) {
      parts.add(
        Text(
          'Verifier will retain',
          style: TextStyle(color: Colors.orange.shade700, fontSize: 12),
        ),
      );
    }

    if (parts.isEmpty) return null;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: parts,
    );
  }
}

/// A simplified version that wraps fields in a scrollable list.
class SelectiveDisclosureFieldsList extends StatelessWidget {
  const SelectiveDisclosureFieldsList({
    super.key,
    required this.fields,
    required this.selectedIds,
    required this.onFieldToggled,
    this.title,
    this.padding = const EdgeInsets.all(16),
  });

  final List<SelectiveDisclosureFieldData> fields;
  final Set<String> selectedIds;
  final void Function(String id, bool isSelected) onFieldToggled;
  final String? title;
  final EdgeInsets padding;

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: padding,
      children: [
        SelectiveDisclosureFields(
          fields: fields,
          selectedIds: selectedIds,
          onFieldToggled: onFieldToggled,
          title: title,
        ),
      ],
    );
  }
}
