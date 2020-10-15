package io.github.bensku.tsbind;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedParameterDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;

import io.github.bensku.tsbind.ast.Constructor;
import io.github.bensku.tsbind.ast.Field;
import io.github.bensku.tsbind.ast.Getter;
import io.github.bensku.tsbind.ast.Member;
import io.github.bensku.tsbind.ast.Method;
import io.github.bensku.tsbind.ast.Parameter;
import io.github.bensku.tsbind.ast.Setter;
import io.github.bensku.tsbind.ast.TypeDefinition;
import io.github.bensku.tsbind.ast.TypeRef;

/**
 * Reads Java source code to produce AST of types and their members.
 * No <i>code</i> is actually analyzed.
 *
 */
public class AstGenerator {

	private final JavaParser parser;
	
	public AstGenerator(JavaParser parser) {
		this.parser = parser;
	}
	
	/**
	 * Parses type AST from source code.
	 * @param source Source unit (single Java file).
	 * @return Parsed type or empty optional if it is not public.
	 */
	public Optional<TypeDefinition> parseType(SourceUnit source) {
		ParseResult<CompilationUnit> result = parser.parse(source.code);
		if (!result.isSuccessful()) {
			//throw new IllegalArgumentException("failed to parse given source code: " + result.getProblems());
			System.out.println("failed to parse " + source.name + ": " + result.getProblems());
			return Optional.empty();
		}
		CompilationUnit unit = result.getResult().orElseThrow();
		TypeDeclaration<?> type = unit.findFirst(TypeDeclaration.class).orElseThrow();
		if (type.getAccessSpecifier() == AccessSpecifier.PUBLIC) {
			return processType(source.name, type);			
		} else {
			return Optional.empty();
		}
	}
	
	private List<Parameter> getParameters(ResolvedMethodLikeDeclaration method) {
		List<Parameter> params = new ArrayList<>(method.getNumberOfParams());
		for (int i = 0; i < method.getNumberOfParams(); i++) {
			ResolvedParameterDeclaration param = method.getParam(i);
			params.add(new Parameter(param.getName(), TypeRef.fromType(param.getType())));
		}
		return params;
	}
	
	private String getJavadoc(Node node) {
		return node.getComment().map(comment -> {
			if (comment.isJavadocComment()) {
				return comment.asJavadocComment().getContent();
			}
			return null;
		}).orElse(null);
	}
	
	private Optional<TypeDefinition> processType(String typeName, TypeDeclaration<?> type) {
		ResolvedReferenceTypeDeclaration resolved = type.resolve();		
		TypeRef typeRef = TypeRef.fromDeclaration(typeName, resolved);
		List<Member> members = new ArrayList<>();
		
		// If this is an enum, generate enum constants and compiler-generated methods
		// JavaParser doesn't consider enum constants "members"
		if (type.isEnumDeclaration()) {
			for (EnumConstantDeclaration constant : type.asEnumDeclaration().getEntries()) {
				members.add(new Field(constant.getNameAsString(), typeRef, getJavadoc(constant), true));
			}
			
			members.add(new Method("valueOf", typeRef, List.of(new Parameter("name", TypeRef.STRING)), List.of(), "", true, false));
			members.add(new Method("values", typeRef.makeArray(1), List.of(), List.of(), "", true, false));
		}
		
		// Handle normal members
		for (BodyDeclaration<?> member : type.getMembers()) {
			if (!isPublic(type, member)) {
				continue; // Neither implicitly or explicitly public
			}
			
			// Process type depending on what it is
			if (member.isTypeDeclaration()) {
				// Recursively process an inner type
				TypeDeclaration<?> inner = member.asTypeDeclaration();
				processType(typeName + "." + inner.getNameAsString(), inner).ifPresent(members::add);
			} else if (member.isConstructorDeclaration()) {
				ResolvedConstructorDeclaration constructor = member.asConstructorDeclaration().resolve();
				// Constructor might be generic, but AFAIK TypeScript doesn't support that
				// (constructors of generic classes are, of course, supported)
				members.add(new Constructor(constructor.getName(), TypeRef.VOID, getParameters(constructor), getJavadoc(member)));
			} else if (member.isMethodDeclaration()) {
				ResolvedMethodDeclaration method = member.asMethodDeclaration().resolve();
				
				String name = method.getName();
				TypeRef returnType = TypeRef.fromType(method.getReturnType());
				String methodDoc = getJavadoc(member);
				boolean override = member.getAnnotationByClass(Override.class).isPresent();
				// TODO check if GraalJS works with "is" for boolean getter too
				if (name.length() > 3 && name.startsWith("get") && !method.getReturnType().isVoid()
						&& method.getNumberOfParams() == 0 && method.getTypeParameters().isEmpty()) {
					// GraalJS will make this getter work, somehow
					members.add(new Getter(name, returnType, methodDoc, override));
				} else if (name.length() > 4 && name.startsWith("set") && method.getReturnType().isVoid()
						&& method.getNumberOfParams() == 1 && method.getTypeParameters().isEmpty()) {
					// GraalJS will make this setter work, somehow
					members.add(new Setter(name, TypeRef.fromType(method.getParam(0).getType()), methodDoc, override));
				} else { // Normal method
					// Resolve type parameters and add to member list
					members.add(new Method(name, returnType, getParameters(method),
							method.getTypeParameters().stream().map(TypeRef::fromDeclaration).collect(Collectors.toList()),
							methodDoc, method.isStatic(), override));
				}
			} else if (member.isFieldDeclaration()) {
				FieldDeclaration field = member.asFieldDeclaration();
				NodeList<VariableDeclarator> vars = field.getVariables();
				if (vars.size() == 1) {
					ResolvedFieldDeclaration resolvedField = field.resolve();
					members.add(new Field(resolvedField.getName(), TypeRef.fromType(resolvedField.getType()),
							getJavadoc(member), field.isStatic()));
				} else { // Symbol solver can't resolve this for us
					for (VariableDeclarator var : vars) {
						ResolvedValueDeclaration resolvedVar = var.resolve();
						members.add(new Field(resolvedVar.getName(), TypeRef.fromType(resolvedVar.getType()),
								getJavadoc(member), field.isStatic()));
					}
				}
			}
		}
		
		// Create definition for the class (includes members create above)
		TypeDefinition.Kind typeKind;
		List<TypeRef> superTypes;
		List<TypeRef> interfaces;
		if (type.isClassOrInterfaceDeclaration()) {
			ClassOrInterfaceDeclaration decl = type.asClassOrInterfaceDeclaration();
			typeKind = decl.isInterface() ? TypeDefinition.Kind.INTERFACE : TypeDefinition.Kind.CLASS;
			superTypes = decl.getExtendedTypes().stream().map(t
					-> TypeRef.fromType(t.resolve())).collect(Collectors.toList());
			interfaces = decl.getImplementedTypes().stream().map(t
					-> TypeRef.fromType(t.resolve())).collect(Collectors.toList());
		} else if (type.isEnumDeclaration()) {
			typeKind = TypeDefinition.Kind.ENUM;
			superTypes = List.of(TypeRef.enumSuperClass(typeRef));
			interfaces = List.of();
		} else {
			typeKind = TypeDefinition.Kind.ANNOTATION;
			superTypes = List.of();
			interfaces = List.of();
		}
		
		String javadoc = getJavadoc(type);
		return Optional.of(new TypeDefinition(javadoc, type.isStatic(), typeRef, typeKind,
				superTypes, interfaces, members));
	}
	
	private boolean isPublic(TypeDeclaration<?> type, BodyDeclaration<?> member) {
		// JPMS is ignored for now, would need to parse module infos for that
		AccessSpecifier access = (member instanceof NodeWithModifiers<?>)
				? ((NodeWithModifiers<?>) member).getAccessSpecifier() : AccessSpecifier.PACKAGE_PRIVATE;
		// Members specified as public are ALWAYS public
		if (access == AccessSpecifier.PUBLIC) {
			return true;
		}
		// Default ("package private") access in interfaces is public
		if (access == AccessSpecifier.PACKAGE_PRIVATE && type.isClassOrInterfaceDeclaration()) {
			return type.asClassOrInterfaceDeclaration().isInterface();
		}
		// Enum constants are handled separately, JavaParser doesn't consider them members
		
		return false; // No reason to consider member public
	}
}
