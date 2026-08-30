// Stock stayed safe on mediump for a decade because no round video anywhere ever exceeded
// ~512px (see FINDINGS.md's cross-client survey) - this shader operates on raw gl_FragCoord
// pixel coordinates (unlike stages 0/1/3, which only touch normalized [0,1] texture coordinates
// and are resolution-magnitude-independent), and length(center - gl_FragCoord.xy) squares that
// coordinate internally. At mediump/FP16's ~65504 max representable value, that overflows once
// |center - fragCoord| exceeds ~256 in either axis - i.e. once the frame is wider than ~512px -
// producing infinity/NaN that corrupts the sharp/blur mix into black for every fragment beyond
// that radius. highp has enough range (>3e38) that this never comes close to overflowing at any
// resolution this app will ever produce.
precision highp float;
varying vec2 vTextureCoord;

uniform sampler2D sTexture; // normal texture
uniform sampler2D bTexture; // blur texture
uniform vec2 center; // width, height / 2.0

void main() {
   vec3 textColor = texture2D(sTexture, vTextureCoord).rgb;
   vec3 blurColor = texture2D(bTexture, vTextureCoord).rgb;

   float radius = center.x;
   float d = length(center - gl_FragCoord.xy) - radius;
   float t = clamp(d, 0.0, 1.0);

   vec3 color = mix(textColor, blurColor, t);
   gl_FragColor = vec4(color, 1.0);
}