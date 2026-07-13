/// SpruceKit Mobile Flutter Plugin
///
/// This library provides Flutter bindings for the SpruceKit Mobile SDK.
library sprucekit_mobile;

// Pigeon-generated APIs
export 'pigeon/oid4vci.g.dart';
export 'pigeon/credential_pack.g.dart';
export 'pigeon/oid4vp.g.dart';
export 'pigeon/vcalm.g.dart';
export 'pigeon/oid4vp_mdoc.g.dart';
export 'pigeon/mdl_presentation.g.dart';
// `wrapResponse` is a Pigeon-internal helper duplicated across .g.dart files
// when @FlutterApi or @async callbacks are used. Hide here to avoid an
// ambiguous_export clash with mdl_presentation.g.dart's copy.
export 'pigeon/mdl_reader.g.dart' hide wrapResponse;
export 'pigeon/spruce_utils.g.dart';
export 'pigeon/dc_api.g.dart';

// Platform View widgets
export 'src/scanner.dart';
