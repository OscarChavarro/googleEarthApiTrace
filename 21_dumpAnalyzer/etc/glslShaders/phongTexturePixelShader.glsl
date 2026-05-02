#version 410 core

uniform sampler2D sTexture;
uniform int withTexture;
uniform int numberOfLights;
uniform vec3 lightColorsGlobal[8];
uniform vec3 lightPositionsGlobal[8];
uniform vec3 ambientColor;
uniform vec3 diffuseColor;
uniform vec3 specularColor;
uniform float phongExponent;

in vec3 PGlobal;
in vec3 N;
in vec3 V;
in vec2 uvTextureCoordinate;
layout(location = 0) out vec4 fragColor;

void main()
{
    vec3 normal = normalize(N);
    vec3 viewDir = normalize(V);

    vec3 ambientTerm = ambientColor;
    vec3 diffuseTerm = vec3(0.0);
    vec3 specularTerm = vec3(0.0);

    for ( int i = 0; i < numberOfLights; i++ ) {
        vec3 L = normalize(lightPositionsGlobal[i] - PGlobal);
        vec3 R = reflect(-L, normal);
        diffuseTerm += lightColorsGlobal[i] * diffuseColor * max(dot(normal, L), 0.0);
        specularTerm += lightColorsGlobal[i] * specularColor *
                        pow(max(dot(R, viewDir), 0.0), phongExponent);
    }

    vec3 color = ambientTerm + diffuseTerm + specularTerm;
    if ( withTexture > 0 ) {
        color *= texture(sTexture, uvTextureCoordinate).xyz;
    }

    fragColor = vec4(color, 1.0);
}
