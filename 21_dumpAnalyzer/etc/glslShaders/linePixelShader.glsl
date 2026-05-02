#version 410 core

in vec3 vertexColor;
layout(location = 0) out vec4 fragColor;

void main()
{
    fragColor = vec4(vertexColor, 1.0);
}
