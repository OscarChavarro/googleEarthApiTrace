#version 410 core

uniform sampler2D sTexture;
in vec3 vertexColor;
in float activateTexture;     // input: boolean 1.0 true 0.0 false
in vec2 uvTextureCoordinate;
layout(location = 0) out vec4 fragColor;

void main() {
    if ( activateTexture > 0.0 ) {
        fragColor = vec4(vertexColor, 1.0)*texture(sTexture, uvTextureCoordinate);
    }
    else {
        fragColor = vec4(vertexColor, 1.0);
    }
}
