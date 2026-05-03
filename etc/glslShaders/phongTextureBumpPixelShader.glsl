#version 410 core

uniform sampler2D sTexture;
uniform sampler2D sNormalMap;
uniform int withTexture;
uniform int numberOfLights;
uniform vec3 lightColorsGlobal[8];
uniform vec3 lightPositionsGlobal[8];
uniform vec3 ambientColor;
uniform vec3 diffuseColor;
uniform vec3 specularColor;
uniform float phongExponent;
uniform vec3 bumpScale;

in vec3 PGlobal;
in vec3 N;
in vec3 V;
in vec2 uvTextureCoordinate;
in vec3 T;
in vec3 B;
layout(location = 0) out vec4 fragColor;

void main()
{
    // [BLIN1978b] "Simulation of Wrinkled Surfaces", Sections 2 and 3.
    // We evaluate:
    //   N' = N + D
    //   D = (Fu (N x Pv) - Fv (N x Pu)) / |N|
    // Fu/Fv are estimated from a scalar bump height map F(u,v) with
    // central differences.
    //
    // NOTE: To stay numerically consistent with the current CPU pipeline
    // (NormalMap.importBumpMap + Raytracer), we use the same two-texel span
    // differencing convention without dividing by texel size.
    vec3 Nn = normalize(N);
    vec3 Pu = normalize(T);
    vec3 Pv = normalize(B);

    vec2 texel = 1.0 / vec2(textureSize(sNormalMap, 0));
    vec2 du = vec2(texel.x, 0.0);
    vec2 dv = vec2(0.0, texel.y);

    float fPlusU = texture(sNormalMap, uvTextureCoordinate + du).r;
    float fMinusU = texture(sNormalMap, uvTextureCoordinate - du).r;
    float fPlusV = texture(sNormalMap, uvTextureCoordinate + dv).r;
    float fMinusV = texture(sNormalMap, uvTextureCoordinate - dv).r;

    float Fu = (fPlusU - fMinusU) * bumpScale.x;
    float Fv = (fMinusV - fPlusV) * bumpScale.y;

    vec3 D = (Fu * cross(Nn, Pv) - Fv * cross(Nn, Pu)) * bumpScale.z;
    vec3 perturbedNormal = normalize(Nn + D);

    vec3 viewDir = normalize(V);
    vec3 ambientTerm = ambientColor;
    vec3 diffuseTerm = vec3(0.0);
    vec3 specularTerm = vec3(0.0);

    for ( int i = 0; i < numberOfLights; i++ ) {
        vec3 L = normalize(lightPositionsGlobal[i] - PGlobal);
        vec3 R = reflect(-L, perturbedNormal);
        diffuseTerm += lightColorsGlobal[i] * diffuseColor * max(dot(perturbedNormal, L), 0.0);
        specularTerm += lightColorsGlobal[i] * specularColor *
                        pow(max(dot(R, viewDir), 0.0), phongExponent);
    }

    // Match CPU raytracer: texture modulates diffuse term only.
    vec3 texturedDiffuseTerm = diffuseTerm;
    if ( withTexture > 0 ) {
        texturedDiffuseTerm *= texture(sTexture, uvTextureCoordinate).xyz;
    }
    vec3 color = ambientTerm + texturedDiffuseTerm + specularTerm;

    fragColor = vec4(color, 1.0);
}
