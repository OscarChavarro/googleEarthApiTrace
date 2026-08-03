#version 410 core

uniform sampler2D sTexture;
uniform int withTexture;
uniform int numberOfLights;
uniform vec3 lightColorsGlobal[8];
uniform vec3 lightPositionsGlobal[8];
uniform vec3 ambientColor;
uniform vec3 diffuseColor;

uniform float cookRoughness;
uniform float cookAlpha;
uniform float cookKd;
uniform float cookKs;
uniform vec3 cookF0;

in vec3 PGlobal;
in vec3 N;
in vec3 V;
in vec2 uvTextureCoordinate;
layout(location = 0) out vec4 fragColor;

float beckmannDistribution(float ndotH, float roughness)
{
    if ( ndotH <= 0.0 ) {
        return 0.0;
    }
    float ndotHSquared = ndotH * ndotH;
    float tanSquaredTheta = (1.0 - ndotHSquared) / max(1e-8, ndotHSquared);
    float mSquared = max(roughness * roughness, 1e-6);
    float exponent = -tanSquaredTheta / mSquared;
    return exp(exponent) / (3.14159265 * mSquared * ndotHSquared * ndotHSquared);
}

float smithSchlickGeometry(float ndotV, float ndotL, float alpha)
{
    float k = alpha * 0.5;
    float gV = ndotV / (ndotV * (1.0 - k) + k);
    float gL = ndotL / (ndotL * (1.0 - k) + k);
    return gV * gL;
}

vec3 schlickFresnel(float vdotH, vec3 f0)
{
    float power = pow(max(0.0, 1.0 - vdotH), 5.0);
    return f0 + (vec3(1.0) - f0) * power;
}

void main()
{
    vec3 normal = normalize(N);
    vec3 viewDir = normalize(V);

    vec3 ambientTerm = ambientColor;
    vec3 diffuseTerm = vec3(0.0);
    vec3 specularTerm = vec3(0.0);

    vec3 textureColor = vec3(1.0);
    if ( withTexture > 0 ) {
        textureColor = texture(sTexture, uvTextureCoordinate).xyz;
    }

    float roughness = max(0.02, cookRoughness);
    float alpha = max(0.0004, cookAlpha);

    for ( int i = 0; i < numberOfLights; i++ ) {
        vec3 L = normalize(lightPositionsGlobal[i] - PGlobal);
        float ndotL = max(dot(normal, L), 0.0);
        if ( ndotL <= 0.0 ) {
            continue;
        }

        float ndotV = max(dot(normal, viewDir), 0.0);
        if ( ndotV <= 0.0 ) {
            continue;
        }

        vec3 H = normalize(L + viewDir);
        float ndotH = max(dot(normal, H), 0.0);
        float vdotH = max(dot(viewDir, H), 0.0);

        float D = beckmannDistribution(ndotH, roughness);
        float G = smithSchlickGeometry(ndotV, ndotL, alpha);
        vec3 F = schlickFresnel(vdotH, cookF0);
        float denominator = max(1e-8, 4.0 * ndotL * ndotV);
        vec3 specular = cookKs * (D * G / denominator) * F;

        vec3 diffuse = cookKd * diffuseColor * textureColor * ndotL;
        diffuseTerm += lightColorsGlobal[i] * diffuse;
        specularTerm += lightColorsGlobal[i] * specular;
    }

    vec3 color = ambientTerm + diffuseTerm + specularTerm;
    fragColor = vec4(color, 1.0);
}
